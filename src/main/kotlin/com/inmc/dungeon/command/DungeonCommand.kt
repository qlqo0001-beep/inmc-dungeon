package com.inmc.dungeon.command

import com.inmc.dungeon.Dungeons
import com.inmc.dungeon.DungeonPlugin
import com.inmc.dungeon.def.RoomDef
import com.inmc.dungeon.def.RoomKind
import com.inmc.dungeon.def.Vec
import com.inmc.dungeon.gui.EntryMenu
import com.inmc.dungeon.gui.LobbyMenu
import com.inmc.dungeon.run.Run
import com.inmc.dungeon.util.Ph
import com.inmc.dungeon.verify.Verifier
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kr.inmc.core.util.Durations
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/던전` 한 트리(별칭 `dungeon`). id 는 전부 영문(Brigadier `word` — 한글 인자 함정 6), 방 종류는 한글 명령 단어.
 * 관리 아래는 전부 **시험 도구**다(추천 17 — "어드민이 테스트하기 불편한 게 너무 많아").
 */
class DungeonCommand(private val d: Dungeons, private val plugin: DungeonPlugin) {

    fun register() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(tree().build(), "INMC 로그라이크 던전", listOf("dungeon", "inmcdungeon"))
        }
    }

    private fun sender(ctx: CommandContext<CommandSourceStack>): CommandSender = ctx.source.sender

    private fun player(ctx: CommandContext<CommandSourceStack>): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { d.messages.send(sender(ctx), "player-only") }

    private fun isAdmin(source: CommandSourceStack): Boolean = source.sender.hasPermission(Dungeons.ADMIN)

    /** 도움말 — 관리자 줄은 권한이 있을 때만(2026-10-08). */
    private fun help(sender: CommandSender) {
        d.messages.send(sender, "help")
        if (sender.hasPermission(Dungeons.ADMIN)) d.messages.send(sender, "help-admin")
    }

    private val dungeonIds = SuggestionProvider<CommandSourceStack> { _, builder ->
        d.library.dungeons.keys.forEach(builder::suggest)
        builder.buildFuture()
    }

    private val difficultyIds = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val dungeon = runCatching { d.library.dungeons[StringArgumentType.getString(ctx, "던전")] }.getOrNull()
        (dungeon?.difficulties?.map { it.id } ?: d.library.dungeons.values.flatMap { it.difficulties.map { x -> x.id } }.distinct()).forEach(builder::suggest)
        builder.buildFuture()
    }

    private val roomIds = SuggestionProvider<CommandSourceStack> { _, builder ->
        d.library.rooms.keys.forEach(builder::suggest)
        builder.buildFuture()
    }

    private fun tree(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("던전")
            .executes { ctx ->
                val player = ctx.source.executor as? Player ?: ctx.source.sender as? Player
                val lobby = player?.let { d.lobbies.of(it.uniqueId) }
                if (player != null && lobby != null) LobbyMenu(d, player, lobby.id).show() else help(sender(ctx))
                1
            }
            .then(Commands.literal("도움말").executes { ctx -> help(sender(ctx)); 1 })
            .then(party())
            .then(
                Commands.literal("순위")
                    .executes { ctx -> ranking(ctx, null, null) }
                    .then(
                        Commands.argument("던전", StringArgumentType.word()).suggests(dungeonIds)
                            .executes { ctx -> ranking(ctx, StringArgumentType.getString(ctx, "던전"), null) }
                            .then(Commands.argument("난이도", StringArgumentType.word()).suggests(difficultyIds).executes { ctx ->
                                ranking(ctx, StringArgumentType.getString(ctx, "던전"), StringArgumentType.getString(ctx, "난이도"))
                            }),
                    ),
            )
            .then(Commands.literal("나가기").executes { ctx ->
                val player = player(ctx) ?: return@executes 0
                if (d.preview.end(player)) {
                    d.messages.send(player, "preview-ended")
                    return@executes 1
                }
                if (d.runs.of(player.uniqueId) == null) d.messages.send(player, "not-in-run") else d.runs.leave(player)
                1
            })
            .then(admin())

    private fun admin(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("관리").requires(::isAdmin)
            .executes { ctx -> help(sender(ctx)); 1 }
            .then(Commands.literal("화면").executes { ctx -> player(ctx)?.let { com.inmc.dungeon.gui.AdminMenu(d, it).show() }; 1 })
            .then(
                Commands.literal("시작").then(
                    Commands.argument("던전", StringArgumentType.word()).suggests(dungeonIds).then(
                        Commands.argument("난이도", StringArgumentType.word()).suggests(difficultyIds)
                            .executes { ctx -> start(ctx, null) }
                            .then(Commands.argument("시드", LongArgumentType.longArg()).executes { ctx -> start(ctx, LongArgumentType.getLong(ctx, "시드")) }),
                    ),
                ),
            )
            .then(Commands.literal("목록").executes { ctx -> list(sender(ctx)); 1 })
            .then(
                Commands.literal("순위").then(
                    Commands.literal("초기화").then(
                        Commands.argument("던전", StringArgumentType.word()).suggests(dungeonIds)
                            .executes { ctx -> resetRanking(ctx, null) }
                            .then(Commands.argument("난이도", StringArgumentType.word()).suggests(difficultyIds).executes { ctx ->
                                resetRanking(ctx, StringArgumentType.getString(ctx, "난이도"))
                            }),
                    ),
                ),
            )
            .then(
                Commands.literal("끝내기")
                    .executes { ctx -> player(ctx)?.let { p -> d.runs.of(p.uniqueId)?.let { end(sender(ctx), it) } ?: d.messages.send(p, "not-in-run") }; 1 }
                    .then(Commands.argument("판", IntegerArgumentType.integer(1)).executes { ctx ->
                        val id = IntegerArgumentType.getInteger(ctx, "판")
                        d.runs.get(id)?.let { end(sender(ctx), it) } ?: d.messages.send(sender(ctx), "admin-no-runs")
                        1
                    }),
            )
            .then(Commands.literal("클리어").executes { ctx ->
                val run = ownRun(ctx) ?: return@executes 0
                d.messages.send(sender(ctx), "admin-cleared", Ph.of().count(d.runs.forceClear(run)))
                1
            })
            .then(
                Commands.literal("다음").then(Commands.argument("방", StringArgumentType.word()).suggests(roomIds).executes { ctx ->
                    val run = ownRun(ctx) ?: return@executes 0
                    val id = StringArgumentType.getString(ctx, "방")
                    val room = d.library.rooms[id] ?: return@executes 0.also { d.messages.send(sender(ctx), "unknown-room", Ph.of().id(id)) }
                    d.messages.send(sender(ctx), if (d.runs.forceNext(run, room)) "admin-next" else "admin-next-busy", Ph.of().room(room.displayName))
                    1
                }),
            )
            .then(
                Commands.literal("투표").then(Commands.argument("번호", IntegerArgumentType.integer(1, 4)).executes { ctx ->
                    val player = player(ctx) ?: return@executes 0
                    val run = ownRun(ctx) ?: return@executes 0
                    d.runs.vote(player, run.id, IntegerArgumentType.getInteger(ctx, "번호") - 1)
                    1
                }),
            )
            .then(Commands.literal("문").executes { ctx ->
                val run = ownRun(ctx) ?: return@executes 0
                if (!d.runs.forceDoor(run)) d.messages.send(sender(ctx), "admin-door-closed")
                1
            })
            .then(
                Commands.literal("비밀")
                    .executes { ctx -> secret(ctx, hide = false) }
                    .then(Commands.literal("찾기").executes { ctx -> secret(ctx, hide = false) })
                    .then(Commands.literal("숨김").executes { ctx -> secret(ctx, hide = true) }),
            )
            .then(
                Commands.literal("금화").then(Commands.argument("수", IntegerArgumentType.integer(1, 100000)).executes { ctx ->
                    val player = player(ctx) ?: return@executes 0
                    val run = ownRun(ctx) ?: return@executes 0
                    run.assisted = true
                    run.addGold(player.uniqueId, IntegerArgumentType.getInteger(ctx, "수"))
                    d.messages.send(player, "admin-gold", Ph.of().count(IntegerArgumentType.getInteger(ctx, "수")).amount(run.goldOf(player.uniqueId).toString()))
                    1
                }),
            )
            .then(
                Commands.literal("버프").then(Commands.argument("id", StringArgumentType.word()).suggests { _, b -> d.effects.buffs.keys.forEach(b::suggest); b.buildFuture() }.executes { ctx ->
                    val player = player(ctx) ?: return@executes 0
                    val run = ownRun(ctx) ?: return@executes 0
                    val id = StringArgumentType.getString(ctx, "id")
                    if (d.effects.give(run, player, id)) {
                        run.assisted = true
                        d.messages.send(player, "admin-buff", Ph.of().value(d.effects.buffs[id]?.name ?: id))
                    }
                    else d.messages.send(player, "admin-buff-unknown", Ph.of().id(id))
                    1
                }),
            )
            .then(
                // 참조는 `dungeon:revive` · `minecraft:golden_apple` · `inmc:<커스텀아이템>` — 콜론이 있어 word 대신 greedy.
                Commands.literal("아이템").then(Commands.argument("참조", StringArgumentType.greedyString())
                    .suggests { _, b -> com.inmc.dungeon.item.TempItems.Builtin.entries.forEach { b.suggest("dungeon:" + it.id) }; b.buildFuture() }
                    .executes { ctx ->
                        val player = player(ctx) ?: return@executes 0
                        val parts = StringArgumentType.getString(ctx, "참조").trim().split(' ')
                        val stack = d.tempItems.create(parts[0], parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 64) ?: 1)
                            ?: return@executes 0.also { d.messages.send(player, "admin-item-unknown", Ph.of().id(parts[0])) }
                        d.runs.of(player.uniqueId)?.assisted = true
                        player.inventory.addItem(stack).values.forEach { player.world.dropItemNaturally(player.location, it) }
                        d.messages.send(player, "admin-item", Ph.of().value(parts[0]))
                        1
                    }),
            )
            .then(
                Commands.literal("시간").then(Commands.argument("초", IntegerArgumentType.integer(-36000, 36000)).executes { ctx ->
                    val run = ownRun(ctx) ?: return@executes 0
                    val seconds = IntegerArgumentType.getInteger(ctx, "초")
                    d.runs.addTime(run, seconds)
                    d.messages.send(sender(ctx), "admin-time", Ph.of().value(seconds.toString()))
                    1
                }),
            )
            .then(
                Commands.literal("부활").then(Commands.argument("수", IntegerArgumentType.integer(-100, 100)).executes { ctx ->
                    val run = ownRun(ctx) ?: return@executes 0
                    run.assisted = true
                    run.revivesLeft = (run.revivesLeft + IntegerArgumentType.getInteger(ctx, "수")).coerceAtLeast(0)
                    d.messages.send(sender(ctx), "admin-revives", Ph.of().count(run.revivesLeft))
                    1
                }),
            )
            .then(Commands.literal("리로드").executes { ctx ->
                val sender = sender(ctx)
                plugin.reload { d.messages.send(sender, "reloaded", Ph.of().count(d.library.dungeons.size).amount(d.library.rooms.size.toString())) }
                1
            })
            .then(Commands.literal("검증").executes { ctx -> Verifier(d).run(sender(ctx)); 1 })
            .then(rooms())
            .then(
                // 내 자리에 포탈(시험) · 포탈 모양 저장.
                Commands.literal("포탈")
                    .then(
                        Commands.argument("던전", StringArgumentType.word()).suggests(dungeonIds)
                            .executes { ctx -> spawnPortal(ctx, null) }
                            .then(Commands.argument("난이도", StringArgumentType.word()).suggests(difficultyIds).executes { ctx ->
                                spawnPortal(ctx, StringArgumentType.getString(ctx, "난이도"))
                            }),
                    )
                    .then(
                        Commands.literal("저장").then(
                            Commands.argument("id", StringArgumentType.word())
                                .executes { ctx ->
                                    val player = player(ctx) ?: return@executes 0
                                    val (min, max) = d.schematics.selection(player) ?: return@executes 0.also { d.messages.send(player, "room-no-selection") }
                                    savePortal(sender(ctx), StringArgumentType.getString(ctx, "id"), player.world, min, max)
                                }
                                .then(
                                    Commands.argument("월드", ArgumentTypes.world()).then(
                                        Commands.argument("좌표1", ArgumentTypes.blockPosition()).then(
                                            Commands.argument("좌표2", ArgumentTypes.blockPosition()).executes { ctx ->
                                                val (min, max) = corners(ctx)
                                                savePortal(sender(ctx), StringArgumentType.getString(ctx, "id"), ctx.getArgument("월드", World::class.java), min, max)
                                            },
                                        ),
                                    ),
                                ),
                        ),
                    ),
            )
            .then(
                Commands.literal("입장화면").then(Commands.argument("던전", StringArgumentType.word()).suggests(dungeonIds).executes { ctx ->
                    val player = player(ctx) ?: return@executes 0
                    val id = StringArgumentType.getString(ctx, "던전")
                    val dungeon = d.library.dungeons[id] ?: return@executes 0.also { d.messages.send(player, "unknown-dungeon", Ph.of().id(id)) }
                    EntryMenu(d, player, dungeon, d.portals.pickDifficulty(dungeon), null).show()
                    1
                }),
            )
            .then(
                // 난이도 해금 고치기(choose 시험) — 난이도를 주면 그것과 그 앞을 깬 것으로, 초기화면 전부 지운다.
                Commands.literal("해금").then(
                    Commands.argument("플레이어", StringArgumentType.word()).suggests(onlinePlayers).then(
                        Commands.argument("던전", StringArgumentType.word()).suggests(dungeonIds)
                            .then(Commands.literal("초기화").executes { ctx -> unlock(ctx, null) })
                            .then(Commands.argument("난이도", StringArgumentType.word()).suggests(difficultyIds).executes { ctx ->
                                unlock(ctx, StringArgumentType.getString(ctx, "난이도"))
                            }),
                    ),
                ),
            )

    private fun rooms(): LiteralArgumentBuilder<CommandSourceStack> {
        val save = Commands.literal("저장").then(
            RoomKind.entries.fold(Commands.argument("id", StringArgumentType.word())) { node, kind ->
                node.then(
                    Commands.literal(kind.word)
                        .executes { ctx -> saveFromSelection(ctx, kind) }
                        .then(
                            Commands.argument("월드", ArgumentTypes.world()).then(
                                Commands.argument("좌표1", ArgumentTypes.blockPosition()).then(
                                    Commands.argument("좌표2", ArgumentTypes.blockPosition()).executes { ctx -> saveFromCorners(ctx, kind) },
                                ),
                            ),
                        ),
                )
            },
        )
        return Commands.literal("방")
            .then(save)
            .then(Commands.literal("목록").executes { ctx -> listRooms(sender(ctx)); 1 })
            .then(Commands.literal("삭제").then(Commands.argument("id", StringArgumentType.word()).suggests(roomIds).executes { ctx ->
                val id = StringArgumentType.getString(ctx, "id")
                d.messages.send(sender(ctx), if (d.library.removeRoom(id)) "room-deleted" else "unknown-room", Ph.of().id(id))
                1
            }))
            .then(Commands.literal("시험").then(Commands.argument("id", StringArgumentType.word()).suggests(roomIds).executes { ctx ->
                val player = player(ctx) ?: return@executes 0
                val id = StringArgumentType.getString(ctx, "id")
                val room = d.library.rooms[id] ?: return@executes 0.also { d.messages.send(player, "unknown-room", Ph.of().id(id)) }
                d.preview.start(player, room)?.let { d.messages.send(player, it, Ph.of().count(d.runs.cells.max)) }
                1
            }))
            .then(Commands.literal("시험끝").executes { ctx ->
                val player = player(ctx) ?: return@executes 0
                d.messages.send(player, if (d.preview.end(player)) "preview-ended" else "preview-none")
                1
            })
    }

    private val onlinePlayers = SuggestionProvider<CommandSourceStack> { _, builder ->
        Bukkit.getOnlinePlayers().forEach { builder.suggest(it.name) }
        builder.buildFuture()
    }

    /** `/던전 파티 …` — 화면 없이도 되게(채팅 [수락]·[참여하기] 클릭이 이 명령을 부른다). */
    private fun party(): LiteralArgumentBuilder<CommandSourceStack> {
        fun lobbyId(ctx: CommandContext<CommandSourceStack>) = IntegerArgumentType.getInteger(ctx, "번호")
        fun withLobby(ctx: CommandContext<CommandSourceStack>, action: (Player, com.inmc.dungeon.party.Lobby) -> Unit): Int {
            val player = player(ctx) ?: return 0
            val lobby = d.lobbies.of(player.uniqueId) ?: return 0.also { d.messages.send(player, "party-none") }
            action(player, lobby)
            return 1
        }
        return Commands.literal("파티").requires { it.sender.hasPermission(Dungeons.PLAY) }
            .executes { ctx -> withLobby(ctx) { player, lobby -> LobbyMenu(d, player, lobby.id).show() } }
            .then(Commands.literal("초대").then(Commands.argument("플레이어", StringArgumentType.word()).suggests(onlinePlayers).executes { ctx ->
                withLobby(ctx) { player, lobby ->
                    val target = Bukkit.getPlayerExact(StringArgumentType.getString(ctx, "플레이어"))
                    if (target == null) d.messages.send(player, "party-target-offline")
                    else d.lobbies.invite(lobby, player, target)?.let { d.messages.send(player, it, Ph.of().player(target.name)) }
                }
            }))
            .then(Commands.literal("수락").then(Commands.argument("번호", IntegerArgumentType.integer(1)).executes { ctx ->
                val player = player(ctx) ?: return@executes 0
                d.lobbies.accept(player, lobbyId(ctx))?.let { d.messages.send(player, it) }
                1
            }))
            .then(Commands.literal("거절").then(Commands.argument("번호", IntegerArgumentType.integer(1)).executes { ctx ->
                player(ctx)?.let { d.lobbies.decline(it, lobbyId(ctx)) }
                1
            }))
            .then(Commands.literal("참여").then(Commands.argument("번호", IntegerArgumentType.integer(1)).executes { ctx ->
                val player = player(ctx) ?: return@executes 0
                val error = d.lobbies.join(player, lobbyId(ctx))
                if (error != null) d.messages.send(player, error) else d.lobbies.of(player.uniqueId)?.let { LobbyMenu(d, player, it.id).show() }
                1
            }))
            .then(Commands.literal("나가기").executes { ctx -> withLobby(ctx) { player, _ -> d.lobbies.leave(player) } })
            .then(Commands.literal("추방").then(Commands.argument("플레이어", StringArgumentType.word()).suggests(onlinePlayers).executes { ctx ->
                withLobby(ctx) { player, lobby ->
                    val name = StringArgumentType.getString(ctx, "플레이어")
                    val target = lobby.members.firstOrNull { Bukkit.getOfflinePlayer(it).name.equals(name, ignoreCase = true) }
                    if (target == null) d.messages.send(player, "party-not-member")
                    else d.lobbies.kick(lobby, player, target)?.let { d.messages.send(player, it) }
                }
            }))
            .then(Commands.literal("모집").executes { ctx ->
                withLobby(ctx) { player, lobby ->
                    d.messages.send(player, d.lobbies.recruit(lobby, player) ?: "party-recruited", Ph.of().value(d.config.recruitCooldown.toString()))
                }
            })
            .then(Commands.literal("입장").executes { ctx ->
                withLobby(ctx) { player, lobby ->
                    val error = d.lobbies.start(lobby, player)
                    if (!error.isNullOrEmpty()) d.messages.send(player, error, Ph.of().count(lobby.dungeon.party.min).amount(lobby.dungeon.party.max.toString()))
                }
            })
    }

    // --- 동작 -------------------------------------------------------------------------

    private fun spawnPortal(ctx: CommandContext<CommandSourceStack>, difficultyId: String?): Int {
        val player = player(ctx) ?: return 0
        val id = StringArgumentType.getString(ctx, "던전")
        val dungeon = d.library.dungeons[id] ?: return 0.also { d.messages.send(player, "unknown-dungeon", Ph.of().id(id)) }
        val difficulty = if (difficultyId == null) d.portals.pickDifficulty(dungeon)
        else dungeon.difficulty(difficultyId) ?: return 0.also { d.messages.send(player, "unknown-difficulty", Ph.of().id(difficultyId)) }
        val portal = d.portals.spawn(dungeon, difficulty, player, player.location.clone().add(player.location.direction.setY(0).normalize().multiply(3))) ?: return 0
        d.messages.send(player, "admin-portal", Ph.of().id(portal.id.toString()).dungeon(dungeon.name).difficulty(difficulty.name))
        return 1
    }

    private fun savePortal(sender: CommandSender, id: String, world: World, min: Vec, max: Vec): Int {
        if (!RoomDef.validId(id)) return 0.also { d.messages.send(sender, "room-bad-id") }
        val (shape, error) = d.portals.capture(id, world, min, max)
        if (shape == null) d.messages.send(sender, "portal-save-failed", Ph.of().value(error ?: "?"))
        else d.messages.send(sender, "portal-saved", Ph.of().id(id).count(shape.blocks.size))
        return 1
    }

    private fun unlock(ctx: CommandContext<CommandSourceStack>, difficultyId: String?): Int {
        val name = StringArgumentType.getString(ctx, "플레이어")
        val target = Bukkit.getPlayerExact(name) ?: Bukkit.getOfflinePlayerIfCached(name)
            ?: return 0.also { d.messages.send(sender(ctx), "party-target-offline") }
        val dungeonId = StringArgumentType.getString(ctx, "던전")
        val dungeon = d.library.dungeons[dungeonId] ?: return 0.also { d.messages.send(sender(ctx), "unknown-dungeon", Ph.of().id(dungeonId)) }
        com.inmc.dungeon.player.Unlocks.reset(target.uniqueId, dungeon.id)
        if (difficultyId != null) {
            val difficulty = dungeon.difficulty(difficultyId) ?: return 0.also { d.messages.send(sender(ctx), "unknown-difficulty", Ph.of().id(difficultyId)) }
            for (each in dungeon.difficulties.take(dungeon.difficulties.indexOf(difficulty) + 1)) {
                com.inmc.dungeon.player.Unlocks.record(target.uniqueId, dungeon.id, each.id)
            }
        }
        d.messages.send(
            sender(ctx), "admin-unlock",
            Ph.of().player(target.name ?: name).dungeon(dungeon.name).value(if (difficultyId == null) "초기화" else "$difficultyId 까지 깬 것으로"),
        )
        return 1
    }

    private fun corners(ctx: CommandContext<CommandSourceStack>): Pair<Vec, Vec> {
        val a = ctx.getArgument("좌표1", BlockPositionResolver::class.java).resolve(ctx.source)
        val b = ctx.getArgument("좌표2", BlockPositionResolver::class.java).resolve(ctx.source)
        return Vec(minOf(a.blockX(), b.blockX()), minOf(a.blockY(), b.blockY()), minOf(a.blockZ(), b.blockZ())) to
            Vec(maxOf(a.blockX(), b.blockX()), maxOf(a.blockY(), b.blockY()), maxOf(a.blockZ(), b.blockZ()))
    }

    private fun start(ctx: CommandContext<CommandSourceStack>, seed: Long?): Int {
        val player = player(ctx) ?: return 0
        val dungeonId = StringArgumentType.getString(ctx, "던전")
        val dungeon = d.library.dungeons[dungeonId] ?: return 0.also { d.messages.send(sender(ctx), "unknown-dungeon", Ph.of().id(dungeonId)) }
        val difficultyId = StringArgumentType.getString(ctx, "난이도")
        val difficulty = dungeon.difficulty(difficultyId) ?: return 0.also { d.messages.send(sender(ctx), "unknown-difficulty", Ph.of().id(difficultyId)) }
        val error = d.runs.start(dungeon, difficulty, listOf(player), player, seed)
        if (error != null) {
            d.messages.send(sender(ctx), error, Ph.of().count(d.runs.cells.max))
            return 0
        }
        val run = d.runs.of(player.uniqueId) ?: return 1
        d.messages.send(sender(ctx), "admin-started", Ph.of().id(run.id.toString()).dungeon(dungeon.name).difficulty(difficulty.name).value(run.seed.toString()))
        return 1
    }

    /** `/던전 순위 [던전] [난이도]` — 빼면 첫 던전 · 첫 난이도. */
    private fun ranking(ctx: CommandContext<CommandSourceStack>, dungeonId: String?, difficultyId: String?): Int {
        val sender = sender(ctx)
        val dungeon = (if (dungeonId == null) d.library.dungeons.values.firstOrNull() else d.library.dungeons[dungeonId])
            ?: return 0.also { d.messages.send(sender, "unknown-dungeon", Ph.of().id(dungeonId ?: "-")) }
        val difficulty = (if (difficultyId == null) dungeon.difficulties.firstOrNull() else dungeon.difficulty(difficultyId))
            ?: return 0.also { d.messages.send(sender, "unknown-difficulty", Ph.of().id(difficultyId ?: "-")) }
        d.messages.send(sender, "rank-header", Ph.of().dungeon(dungeon.name).difficulty(difficulty.name))
        val top = d.records.top(dungeon.id, difficulty.id, RANK_LINES)
        if (top.isEmpty()) d.messages.send(sender, "rank-empty")
        top.forEachIndexed { index, entry ->
            d.messages.send(sender, "rank-line", Ph.of().count(index + 1).player(entry.name).amount(Durations.formatShort(entry.best)).value(entry.clears.toString()))
        }
        val player = sender as? Player ?: return 1
        val mine = d.records.entryOf(dungeon.id, difficulty.id, player.uniqueId) ?: return 1
        d.messages.send(sender, "rank-mine", Ph.of().count(d.records.rankOf(dungeon.id, difficulty.id, player.uniqueId) ?: 0).amount(Durations.formatShort(mine.best)))
        return 1
    }

    private fun resetRanking(ctx: CommandContext<CommandSourceStack>, difficultyId: String?): Int {
        val id = StringArgumentType.getString(ctx, "던전")
        d.messages.send(sender(ctx), "admin-rank-reset", Ph.of().id(id).count(d.records.reset(id, difficultyId)))
        return 1
    }

    private fun ownRun(ctx: CommandContext<CommandSourceStack>): Run? {
        val player = player(ctx) ?: return null
        return d.runs.of(player.uniqueId) ?: null.also { d.messages.send(player, "not-in-run") }
    }

    /** `/던전 관리 비밀 [찾기|숨김]` — 숨은 자리를 찾은 것으로 · 다음 선택지 하나를 "???" 로. 상한을 넘어도 낸다. */
    private fun secret(ctx: CommandContext<CommandSourceStack>, hide: Boolean): Int {
        val player = player(ctx) ?: return 0
        val run = ownRun(ctx) ?: return 0
        val ok = if (hide) d.runs.forceHidden(run) else d.runs.revealSecret(run, player, force = true)
        if (!ok) d.messages.send(player, "admin-secret-fail")
        else d.messages.send(player, if (hide) "admin-secret-hidden" else "admin-secret-found")
        return 1
    }

    private fun end(sender: CommandSender, run: Run) {
        d.runs.finish(run, success = false, reason = "admin")
        d.runs.end(run)
        d.messages.send(sender, "admin-ended", Ph.of().id(run.id.toString()))
    }

    private fun list(sender: CommandSender) {
        val runs = d.runs.all()
        if (runs.isEmpty()) {
            d.messages.send(sender, "admin-no-runs")
            return
        }
        val now = System.currentTimeMillis()
        d.messages.send(sender, "admin-run-header", Ph.of().count(d.runs.cells.activeCount).amount(d.runs.cells.max.toString()))
        for (run in runs) {
            val names = run.members.joinToString(", ") { Bukkit.getOfflinePlayer(it).name ?: "?" }
            val left = run.timeLeftMs(now)?.let { Durations.formatShort(it / 1000) } ?: "-"
            d.messages.send(
                sender, "admin-run-line",
                Ph.of().id(run.id.toString()).dungeon(run.dungeon.name).difficulty(run.difficulty.name).player(names)
                    .room(run.current?.def?.displayName ?: "-").value("${run.phase.name.lowercase()} · 방 ${run.step} · 남은 $left"),
            )
        }
    }

    private fun listRooms(sender: CommandSender) {
        val rooms = d.library.rooms.values
        d.messages.send(sender, "room-list-header", Ph.of().count(rooms.size))
        for (room in rooms.sortedWith(compareBy({ it.theme }, { it.kind.ordinal }, { it.id }))) {
            d.messages.send(
                sender, "room-list-line",
                Ph.of().id(room.id).room(room.displayName).value(room.theme.ifBlank { "(없음)" })
                    .amount("출구 ${room.markers.exits.size} · 몬스터 자리 ${room.markers.mobs.size}"),
            )
        }
    }

    private fun saveFromSelection(ctx: CommandContext<CommandSourceStack>, kind: RoomKind): Int {
        val player = player(ctx) ?: return 0
        val (min, max) = d.schematics.selection(player) ?: return 0.also { d.messages.send(player, "room-no-selection") }
        return save(sender(ctx), StringArgumentType.getString(ctx, "id"), kind, player.world, min, max)
    }

    private fun saveFromCorners(ctx: CommandContext<CommandSourceStack>, kind: RoomKind): Int {
        val (min, max) = corners(ctx)
        return save(sender(ctx), StringArgumentType.getString(ctx, "id"), kind, ctx.getArgument("월드", World::class.java), min, max)
    }

    /** 고친 정의(테마·몬스터 등)는 다시 저장해도 남는다 — 건축물·크기·표시만 새로. */
    private fun save(sender: CommandSender, id: String, kind: RoomKind, world: World, min: Vec, max: Vec): Int {
        if (!RoomDef.validId(id)) return 0.also { d.messages.send(sender, "room-bad-id") }
        val span = maxOf(max.x - min.x + 1, max.z - min.z + 1)
        if (span > d.config.slotSpacing - 8) {
            d.messages.send(sender, "room-too-big", Ph.of().value("${max.x - min.x + 1}×${max.z - min.z + 1}").count(d.config.slotSpacing))
            return 0
        }
        d.schematics.capture(world, min, max, d.library.schematic(id)) { captured, error ->
            if (captured == null) {
                d.messages.send(sender, "room-save-failed", Ph.of().value(error ?: "?"))
                return@capture
            }
            val previous = d.library.rooms[id]
            val room = (previous?.copy(kind = kind) ?: RoomDef(id, kind)).copy(size = captured.size, markers = captured.markers)
            d.library.putRoom(room)
            d.messages.send(
                sender, "room-saved",
                Ph.of().id(id).room(room.displayName).value(captured.size.toString())
                    .amount(if (captured.markers.arrival == null) "없음" else "있음").count(captured.markers.exits.size),
            )
            captured.problems.forEach { d.messages.send(sender, "room-save-problem", Ph.of().value(it)) }
        }
        return 1
    }

    private companion object {
        /** `/던전 순위` 에 보이는 줄 수. */
        const val RANK_LINES = 10
    }
}
