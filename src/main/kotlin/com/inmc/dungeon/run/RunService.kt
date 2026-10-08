package com.inmc.dungeon.run

import com.inmc.dungeon.Dungeons
import com.inmc.dungeon.def.Difficulty
import com.inmc.dungeon.def.DungeonDef
import com.inmc.dungeon.def.RoomDef
import com.inmc.dungeon.def.RoomKind
import com.inmc.dungeon.def.Vec
import com.inmc.dungeon.player.Returns
import com.inmc.dungeon.util.Ph
import com.inmc.dungeon.world.Cells
import com.inmc.monster.api.MonsterAPI
import com.inmc.monster.spawn.SpawnOptions
import kr.inmc.core.event.InmcSignalEvent
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import java.time.Duration
import java.util.UUID

/**
 * 판의 시작부터 끝까지. **전부 메인 스레드** — 붙여넣기는 워커에서 돌고 콜백이 메인으로 돌아온다.
 *
 * 흐름: [start] → 시작의 방 [enter] → (싸움) → [roomCleared] → [offerChoices](투표) → [decide] → 붙이기 → 문 열림 → [transfer] → [enter] … → 보스 → [finish] → [end].
 */
class RunService(private val d: Dungeons) {

    private val runs = LinkedHashMap<Int, Run>()

    /** 고치는 것은 메인뿐이지만 비동기 접속 사건이 [ofAsync] 로 읽는다. */
    private val byPlayer = java.util.concurrent.ConcurrentHashMap<UUID, Run>()
    private var nextId = 1

    val cells = Cells(d.config.maxActive)

    /** 우리가 밖으로 보내는 중인 사람 — 그 순간이동을 "스스로 나감"으로 오해하지 않게. */
    private val exiting = HashSet<UUID>()

    private val healthKey = NamespacedKey(com.inmc.dungeon.Dungeons.NAMESPACE, "health")

    fun all(): Collection<Run> = runs.values

    fun get(id: Int): Run? = runs[id]

    fun of(player: UUID): Run? = byPlayer[player]

    /** 비동기 사건용 — 판에 들어 있는지만. */
    fun ofAsync(player: UUID): Boolean = byPlayer.containsKey(player)

    fun byTag(tag: String?): Run? = tag?.removePrefix("dungeon:")?.toIntOrNull()?.let(runs::get)?.takeIf { it.tag == tag }

    fun isExiting(player: UUID): Boolean = player in exiting

    // --- 시작 -----------------------------------------------------------------------

    /** 판을 연다. 안 되면 메시지 키(그 까닭). */
    fun start(dungeon: DungeonDef, difficulty: Difficulty, players: List<Player>, leader: Player, seed: Long? = null): String? {
        val world = d.world.world ?: return "world-not-ready"
        if (players.any { of(it.uniqueId) != null }) return "already-in-run"
        val starts = d.library.pool(dungeon).filter { it.kind == RoomKind.START }
        if (starts.isEmpty()) return "no-start-room"
        val cell = cells.acquire() ?: return "full"
        val run = Run(nextId++, dungeon, difficulty, seed ?: System.nanoTime(), cell, world, leader.uniqueId, players.map { it.uniqueId })
        runs[run.id] = run
        for (player in players) {
            d.lobbies.forget(player.uniqueId)
            byPlayer[player.uniqueId] = run
            d.returns.remember(player.uniqueId, Returns.Point.of(player.location, player.gameMode))
            d.messages.send(player, "preparing", Ph.of().dungeon(dungeon.name).difficulty(difficulty.name))
        }
        // 시드를 정하면 판을 미리 안다 — 순위에 올리지 않는다.
        if (seed != null) run.assisted = true
        place(run, starts[run.random.nextInt(starts.size)], 0) { enter(run, it) }
        return null
    }

    /** [room] 을 이 판의 자리 [slot] 에 붙이고, 끝나면 [then]. 그 사이에 판이 끝났으면 붙인 것을 치운다. */
    private fun place(run: Run, room: RoomDef, slot: Int, then: (Run.Placed) -> Unit) {
        // 그 자리를 비우는 중이면 다 비운 뒤에 붙인다 — 비우기(워커)가 늦게 끝나 새 방을 지운 일이 있다(2026-10-08 테섭).
        if ((run.clearing[slot] ?: 0) > 0) {
            run.afterClear.getOrPut(slot) { ArrayList() }.add { place(run, room, slot, then) }
            return
        }
        val (x, y, z) = Cells.origin(run.cell, slot, d.config.cellSpacing, d.config.slotSpacing, d.config.baseY)
        val origin = Location(run.world, x.toDouble(), y.toDouble(), z.toDouble())
        val placed = Run.Placed(room, slot, origin)
        d.schematics.paste(d.library.schematic(room.id), run.world, Vec(x, y, z)) { error ->
            if (runs[run.id] !== run || run.phase == Run.Phase.ENDING) {
                tidy(run, placed) {}
                return@paste
            }
            if (error != null) {
                d.logger.warning("판 ${run.id}: 방 '${room.id}' 을(를) 붙이지 못했습니다 — $error")
                finish(run, success = false, reason = "build-failed")
                return@paste
            }
            then(placed)
        }
    }

    // --- 방 -------------------------------------------------------------------------

    private fun enter(run: Run, placed: Run.Placed) {
        val previous = run.current
        if (previous != null) d.features.onLeave(run)
        run.current = placed
        run.next = null
        run.doorAt = null
        run.secretFound = false
        run.choice = null
        val now = System.currentTimeMillis()
        if (placed.def.kind == RoomKind.START) {
            run.waitingSince = now
        } else {
            run.step++
            run.usedKinds.merge(placed.def.kind, 1, Int::plus)
            run.usedRooms.add(placed.def.id)
        }
        val arrival = placed.arrival
        for (id in run.members) {
            val player = Bukkit.getPlayer(id) ?: continue
            if (id in run.offline) continue
            if (id !in run.downed) {
                player.gameMode = GameMode.ADVENTURE
                player.fallDistance = 0f
            }
            player.teleportAsync(arrival)
            player.showTitle(Title.title(Text.renderFlat(placed.def.displayName), Text.renderFlat(roomSubtitle(run, placed)), TITLE_TIMES))
        }
        run.phase = Run.Phase.ROOM
        previous?.let { tidy(run, it) {} }
        RunBar.update(run, now)
        d.features.onEnter(run, placed)
        if (fights(placed.def)) {
            run.wavesLeft = if (isWave(placed.def)) placed.def.waves - 1 else 0
            spawnMobs(run, placed)
        } else roomCleared(run)
    }

    /** 몬스터를 다 잡아야 문이 열리는 방 — 싸우는 종류(방마다 `clear-required`) · 웨이브 이벤트. */
    private fun fights(def: RoomDef): Boolean = (def.clearRequired && def.kind.fights) || isWave(def)

    private fun isWave(def: RoomDef): Boolean = def.kind == RoomKind.EVENT && def.event == "wave"

    private fun roomSubtitle(run: Run, placed: Run.Placed): String = when (placed.def.kind) {
        RoomKind.START -> "<gray>준비가 되면 출구로 — 그때부터 시간이 흐릅니다</gray>"
        RoomKind.BOSS -> "<red>보스를 쓰러뜨리세요</red>"
        else -> "<gray>방 ${run.step}</gray>"
    }

    private fun spawnMobs(run: Run, placed: Run.Placed) {
        val api = MonsterAPI.get()
        if (api == null) {
            tell(run, "monster-missing")
            roomCleared(run)
            return
        }
        val def = placed.def
        val base = if (def.count.first >= def.count.last) def.count.first else run.random.nextInt(def.count.first, def.count.last + 1)
        val count = Scaling.count(base, run.difficulty.mobCount, run.startSize, run.dungeon.party.countPerExtra)
        val health = Scaling.health(run.difficulty.mobHealth, run.startSize, run.dungeon.party.healthPerExtra)
        val options = SpawnOptions(
            tag = run.tag,
            affixIds = run.difficulty.affixes.ifEmpty { null },
            dropsEnabled = false,
            countsTowardBudget = false,
            ignoreRules = true,
        )
        val spots = def.markers.mobs.ifEmpty { listOf(com.inmc.dungeon.def.Markers.MobSpot(def.markers.arrival?.at ?: Vec(def.size.x / 2, 1, def.size.z / 2))) }
        var spawned = 0
        for (i in 0 until count) {
            val spot = spots[i % spots.size]
            val mob = spot.mob ?: pickMob(run, def) ?: continue
            val at = placed.at(spot.at).add(run.random.nextDouble(-0.6, 0.6), 0.0, run.random.nextDouble(-0.6, 0.6))
            val active = api.spawn(mob, at, options)
            if (active == null) {
                d.logger.warning("판 ${run.id}: 몬스터 '$mob' 소환 실패 — ${api.lastRefusal()}")
                continue
            }
            scaleHealth(active.entity, health)
            spawned++
        }
        if (def.kind == RoomKind.BOSS && def.boss != null) {
            val at = def.markers.boss?.let(placed::at) ?: placed.arrival
            val boss = api.spawn(def.boss, at, options)
            if (boss == null) d.logger.warning("판 ${run.id}: 보스 '${def.boss}' 소환 실패 — ${api.lastRefusal()}")
            else {
                scaleHealth(boss.entity, health)
                spawned++
            }
        }
        if (spawned == 0) {
            d.logger.warning("판 ${run.id}: 방 '${def.id}' 에서 몬스터를 하나도 부르지 못했습니다 — 지나갑니다")
            tell(run, "monster-missing")
            roomCleared(run)
            return
        }
        tell(run, if (def.kind == RoomKind.BOSS) "boss-appeared" else "room-fight", Ph.of().count(spawned))
    }

    private fun pickMob(run: Run, def: RoomDef): String? {
        val total = def.mobs.sumOf { it.weight }
        if (total <= 0) return null
        var roll = run.random.nextInt(total)
        for (entry in def.mobs) {
            if (roll < entry.weight) return entry.mob
            roll -= entry.weight
        }
        return def.mobs.last().mob
    }

    private fun scaleHealth(entity: LivingEntity, factor: Double) {
        if (factor == 1.0) return
        val attribute = entity.getAttribute(Attribute.MAX_HEALTH) ?: return
        attribute.removeModifier(healthKey)
        attribute.addModifier(AttributeModifier(healthKey, factor - 1.0, AttributeModifier.Operation.ADD_SCALAR))
        entity.health = attribute.value
    }

    /** 몬스터가 죽었을 때(한 틱 뒤)와 1초마다 — 이 판의 몬스터가 다 사라졌으면 방 클리어. */
    fun checkCleared(run: Run) {
        if (run.phase != Run.Phase.ROOM) return
        val placed = run.current ?: return
        if (!fights(placed.def)) return
        val api = MonsterAPI.get() ?: return
        if (api.countWithTag(run.tag) > 0) return
        if (run.wavesLeft > 0) {
            run.wavesLeft--
            tell(run, "wave-next", Ph.of().count(placed.def.waves - run.wavesLeft).amount(placed.def.waves.toString()))
            spawnMobs(run, placed)
            return
        }
        roomCleared(run)
    }

    private fun roomCleared(run: Run) {
        val placed = run.current ?: return
        if (placed.def.kind != RoomKind.START) {
            for (id in run.members) Bukkit.getPlayer(id)?.let { fireRoomSignal(run, it, placed.def) }
        }
        if (placed.def.kind == RoomKind.BOSS) {
            d.features.placeBossChest(run)
            finish(run, success = true, reason = "cleared")
            return
        }
        if (fights(placed.def)) {
            val bonus = run.dungeon.gold.perRoom
            for (id in run.members) {
                val player = Bukkit.getPlayer(id) ?: continue
                run.addGold(id, bonus)
                d.messages.send(player, "room-cleared", Ph.of().count(bonus).amount(run.goldOf(id).toString()))
            }
            sound(run, Sound.ENTITY_PLAYER_LEVELUP)
        }
        offerChoices(run)
    }

    // --- 선택 -----------------------------------------------------------------------

    private fun offerChoices(run: Run) {
        val placed = run.current ?: return
        val pool = d.library.pool(run.dungeon)
        val slots = placed.def.markers.exits.size.coerceIn(1, 3)
        val kinds = Choices.kinds(
            Choices.Input(
                step = run.step,
                length = run.dungeon.length,
                weights = run.dungeon.weights,
                used = run.usedKinds,
                maxPerKind = run.dungeon.maxPerKind,
                eliteFrom = run.dungeon.eliteFrom,
                available = pool.map { it.kind }.toSet(),
                slots = slots,
            ),
            run.random,
        )
        val taken = HashSet<String>()
        val options = kinds.mapNotNull { kind -> Choices.pickRoom(kind, pool, run.usedRooms, taken, run.random)?.also { taken.add(it.id) } }.toMutableList()
        if (options.isEmpty()) {
            d.logger.warning("판 ${run.id}: 던전 '${run.dungeon.id}' 에 더 고를 방이 없습니다(보스방 없음?) — 클리어로 끝냅니다")
            finish(run, success = true, reason = "no-more-rooms")
            return
        }
        val exits = options.indices.map(placed::exit).toMutableList()
        val hidden = HashSet<Int>()
        // 관리자가 억지로 낸 것(찾음·숨김)은 상한을 넘어도 낸다.
        val forced = run.forceHidden
        run.forceHidden = false
        val available = forced || run.secretFound || secretAvailable(run)
        when (val secret = Choices.secret(options.map { it.kind }, available, run.secretFound, if (forced) 100.0 else run.dungeon.secretChance, run.random)) {
            Choices.Secret.None -> Unit
            // 숨은 자리를 찾았다 — 그 자리가 네 번째 출구.
            Choices.Secret.Extra -> pickSecret(run, taken)?.let {
                options.add(it)
                exits.add(secretSpot(placed))
            }
            // "???" — 선택지 하나를 비밀방으로 바꾸고 정체를 숨긴다. 출구 자리는 그대로.
            is Choices.Secret.Hidden -> pickSecret(run, taken)?.let {
                options[secret.index] = it
                hidden.add(secret.index)
            }
        }
        offer(run, options, exits, hidden)
    }

    /** 이 판에 비밀방을 더 낼 수 있나 — 풀에 비밀방이 있고, 이번 판에 나온 수가 상한(`max-per-kind.secret`, 기본 1) 아래. */
    fun secretAvailable(run: Run): Boolean =
        d.library.pool(run.dungeon).any { it.kind == RoomKind.SECRET } &&
            (run.usedKinds[RoomKind.SECRET] ?: 0) < (run.dungeon.maxPerKind[RoomKind.SECRET] ?: 1)

    /** 비밀방 하나. 같은 선택지에 이미 나온 방은 피하되, 그것밖에 없으면 그것이라도. */
    private fun pickSecret(run: Run, taken: Set<String>): RoomDef? {
        val pool = d.library.pool(run.dungeon)
        return Choices.pickRoom(RoomKind.SECRET, pool, run.usedRooms, taken, run.random)
            ?: Choices.pickRoom(RoomKind.SECRET, pool, run.usedRooms, emptySet(), run.random)
    }

    /** 숨은 `[비밀]` 자리 — 없는 방(관리자 시험)은 도착 자리. */
    private fun secretSpot(placed: Run.Placed): Location = placed.def.markers.secret?.let(placed::at) ?: placed.arrival

    /** [options] 를 [exits] 자리에 선택지로 띄우고 투표를 연다. [hidden] 은 "???" 로 보인다. */
    private fun offer(run: Run, options: List<RoomDef>, exits: List<Location>, hidden: Set<Int> = emptySet()) {
        d.holograms.clear(run)
        val eligible = run.members.filter { run.alive(it) && Bukkit.getPlayer(it) != null }.toSet()
        val ballot = Ballot(options.size, run.leader, eligible, System.currentTimeMillis() + d.config.voteSeconds * 1000L)
        run.choice = Run.ChoiceRound(options, ballot, exits, hidden)
        run.phase = Run.Phase.CHOOSING
        options.forEachIndexed { index, room -> d.holograms.showChoice(run, index, room, exits[index], 0, index in hidden) }
        tell(run, if (options.size == 1) "choose-one" else "choose", Ph.of().count(options.size).value(d.config.voteSeconds.toString()))
        if (hidden.isNotEmpty()) tell(run, "secret-hidden")
    }

    /**
     * 숨은 `[비밀]` 자리를 찾았다(누름 · `/던전 관리 비밀`). 싸우는 중이면 표시만 해 두고 선택지 때 네 번째 출구로,
     * 투표 중이면 지금 선택지에 더한다(마감 연장). 이미 정해졌으면 늦었다. 찾은 것으로 쳤으면 true.
     */
    fun revealSecret(run: Run, player: Player?, force: Boolean = false): Boolean {
        val placed = run.current ?: return false
        val name = player?.name ?: "관리자"
        if (run.secretFound) return false
        if (!force && !secretAvailable(run)) return false
        if (force) run.assisted = true
        if (d.library.pool(run.dungeon).none { it.kind == RoomKind.SECRET }) return false
        when (run.phase) {
            Run.Phase.ROOM -> {
                run.secretFound = true
                tell(run, "secret-found", Ph.of().player(name))
            }
            Run.Phase.CHOOSING -> {
                val round = run.choice ?: return false
                val room = pickSecret(run, round.options.map { it.id }.toSet()) ?: return false
                run.secretFound = true
                val index = round.options.size
                val at = secretSpot(placed)
                run.choice = Run.ChoiceRound(round.options + room, round.ballot.expanded(SECRET_EXTRA_SECONDS * 1000L), round.exits + at, round.hidden)
                d.holograms.showChoice(run, index, room, at, 0)
                tell(run, "secret-found-choice", Ph.of().player(name).value(SECRET_EXTRA_SECONDS.toString()))
            }
            else -> {
                player?.let { d.messages.send(it, "secret-late") }
                return false
            }
        }
        // 찾은 자리는 치운다 — 다시 눌러도 아무 일 없게.
        run.stations.removeAll { entity -> (d.features.stationOf(entity)?.second == RoomFeatures.Station.SECRET).also { if (it) entity.remove() } }
        run.world.spawnParticle(Particle.SCULK_SOUL, secretSpot(placed).clone().add(0.0, 1.0, 0.0), 30, 0.4, 0.6, 0.4, 0.02)
        sound(run, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK)
        return true
    }

    /** 다음 선택지 하나를 "???" 로(`/던전 관리 비밀 숨김`). 투표 중이면 선택지를 다시 굴린다. */
    fun forceHidden(run: Run): Boolean {
        if (d.library.pool(run.dungeon).none { it.kind == RoomKind.SECRET }) return false
        run.assisted = true
        run.forceHidden = true
        if (run.phase == Run.Phase.CHOOSING) offerChoices(run)
        return true
    }

    fun vote(player: Player, runId: Int, index: Int) {
        val run = of(player.uniqueId) ?: return
        if (run.id != runId || run.phase != Run.Phase.CHOOSING) return
        val round = run.choice ?: return
        if (!round.ballot.cast(player.uniqueId, index)) {
            d.messages.send(player, "vote-not-allowed")
            return
        }
        val counts = round.ballot.counts()
        round.options.forEachIndexed { i, room -> d.holograms.update(run, i, room, counts[i], decided = false, i in round.hidden) }
        if (run.members.size > 1) tell(run, "voted", Ph.of().player(player.name).room(nameOf(round, index)))
        tryDecide(run, System.currentTimeMillis())
    }

    /** 선택지 이름 — "???" 는 들어가기 전까지 숨긴다. */
    private fun nameOf(round: Run.ChoiceRound, index: Int): String =
        if (index in round.hidden) "<gray>???</gray>" else round.options[index].displayName

    private fun tryDecide(run: Run, now: Long) {
        val round = run.choice ?: return
        val result = round.ballot.result(now, run.random) ?: return
        decide(run, result)
    }

    private fun decide(run: Run, index: Int) {
        val round = run.choice ?: return
        val room = round.options[index]
        val placed = run.current ?: return
        val hidden = index in round.hidden
        run.phase = Run.Phase.BUILDING
        run.doorAt = round.exits[index]
        d.holograms.clear(run, keep = index)
        d.holograms.update(run, index, room, round.ballot.counts()[index], decided = true, hidden)
        tell(run, "decided", Ph.of().room(nameOf(round, index)))
        place(run, room, 1 - placed.slot) { next ->
            run.next = next
            run.phase = Run.Phase.DOOR_OPEN
            d.holograms.opened(run, index, room, hidden)
            tell(run, "door-open")
            sound(run, Sound.BLOCK_IRON_DOOR_OPEN)
        }
    }

    /** 움직일 때 — 열린 출구에 가까이 갔으면 파티 전체를 다음 방으로. */
    fun tryDoor(player: Player, to: Location) {
        val run = of(player.uniqueId) ?: return
        if (run.phase != Run.Phase.DOOR_OPEN || !run.alive(player.uniqueId)) return
        val exit = run.doorAt ?: return
        if (near(to, exit)) transfer(run)
    }

    private fun near(location: Location, exit: Location): Boolean =
        exit.world == location.world && exit.distanceSquared(location) <= d.config.doorRadius * d.config.doorRadius

    private fun transfer(run: Run) {
        val next = run.next ?: return
        if (run.current?.def?.kind == RoomKind.START && run.timerStartedAt == null) {
            run.timerStartedAt = System.currentTimeMillis()
            tell(run, "timer-started")
        }
        d.holograms.clear(run)
        enter(run, next)
    }

    // --- 관리자 시험 도구 -------------------------------------------------------------

    /** 지금 방의 몬스터를 치운다(`/던전 관리 클리어`). */
    fun forceClear(run: Run): Int {
        run.assisted = true
        val removed = MonsterAPI.get()?.killAllByTag(run.tag) ?: 0
        if (run.phase == Run.Phase.ROOM) roomCleared(run)
        return removed
    }

    /** 다음 방을 [room] 으로 정한다(`/던전 관리 다음`). 싸우는 중이면 몬스터를 치우고. */
    fun forceNext(run: Run, room: RoomDef): Boolean {
        // 이미 정해져 문이 열렸으면 그 방을 치우고 다시 고른다(투표 시간이 지나 저절로 정해진 뒤에도 시험할 수 있게).
        if (run.phase == Run.Phase.DOOR_OPEN) {
            run.next?.let { tidy(run, it) {} }
            run.next = null
            run.phase = Run.Phase.CHOOSING
        }
        if (run.phase != Run.Phase.ROOM && run.phase != Run.Phase.CHOOSING) return false
        run.assisted = true
        MonsterAPI.get()?.killAllByTag(run.tag)
        offer(run, listOf(room), listOf(run.current?.exit(0) ?: return false))
        decide(run, 0)
        return true
    }

    /** 열린 문으로 지금 옮긴다(`/던전 관리 문`). 문이 안 열렸으면 false. */
    fun forceDoor(run: Run): Boolean {
        if (run.phase != Run.Phase.DOOR_OPEN) return false
        transfer(run)
        return true
    }

    fun addTime(run: Run, seconds: Int) {
        run.assisted = true
        run.timerStartedAt?.let { run.timerStartedAt = it + seconds * 1000L }
    }

    // --- 쓰러짐 ---------------------------------------------------------------------

    /** 치명 피해를 막은 뒤 — 진짜로 죽지 않고 쓰러진다(추천 6). 부활 수가 남았으면 대기 뒤 부활, 없으면 끝날 때까지 관전. */
    fun down(run: Run, player: Player) {
        val id = player.uniqueId
        if (id in run.downed || run.phase == Run.Phase.ENDING) return
        player.maxHealth()?.let { player.health = it }
        player.fireTicks = 0
        player.fallDistance = 0f
        player.gameMode = GameMode.SPECTATOR
        val now = System.currentTimeMillis()
        val reviveAt = if (run.revivesLeft > 0) {
            run.revivesLeft--
            now + run.difficulty.reviveDelay * 1000L
        } else null
        run.downed[id] = reviveAt
        run.choice?.ballot?.drop(id)
        player.showTitle(
            Title.title(
                Text.renderFlat("<red>쓰러졌습니다</red>"),
                Text.renderFlat(if (reviveAt == null) "<gray>남은 부활이 없습니다 — 파티를 지켜보세요</gray>" else "<gray>${run.difficulty.reviveDelay}초 뒤 부활</gray>"),
                TITLE_TIMES,
            ),
        )
        tell(run, if (reviveAt == null) "downed-final" else "downed", Ph.of().player(player.name).count(run.revivesLeft))
        keepSpectating(run)
        RunBar.update(run, now)
        if (wiped(run)) finish(run, success = false, reason = "wiped")
    }

    private fun wiped(run: Run): Boolean =
        run.members.none { run.alive(it) } && run.downed.values.none { it != null }

    /** 대기 없이 지금 부활(즉시부활 아이템). */
    fun reviveNow(run: Run, id: UUID) = revive(run, id)

    private fun revive(run: Run, id: UUID) {
        run.downed.remove(id)
        val player = Bukkit.getPlayer(id) ?: return
        val placed = run.current ?: return
        player.gameMode = GameMode.ADVENTURE
        player.maxHealth()?.let { player.health = it }
        player.foodLevel = 20
        // 도착 자리에 몬스터가 몰려 있으면 부활하자마자 다시 쓰러진다 — 잠깐 무적(피해 사건에서 막는다).
        run.graceUntil[id] = System.currentTimeMillis() + REVIVE_GRACE_MS
        player.teleportAsync(placed.arrival)
        run.choice?.ballot?.add(id)
        player.showTitle(Title.title(Text.renderFlat("<green>부활</green>"), Text.renderFlat(""), TITLE_TIMES))
        tell(run, "revived", Ph.of().player(player.name))
    }

    /** 쓰러진 사람의 카메라를 살아 있는 파티원에게 붙인다. 아무도 없으면 방 안에 붙잡아 둔다. */
    private fun keepSpectating(run: Run) {
        val target = run.members.firstNotNullOfOrNull { id -> if (run.alive(id)) Bukkit.getPlayer(id) else null }
        for (id in run.downed.keys) {
            val player = Bukkit.getPlayer(id) ?: continue
            if (player.gameMode != GameMode.SPECTATOR) player.gameMode = GameMode.SPECTATOR
            if (target != null) {
                if (player.spectatorTarget != target) player.spectatorTarget = target
            } else {
                val placed = run.current ?: continue
                if (!placed.contains(player.location, 4.0)) player.teleportAsync(placed.arrival)
            }
        }
    }

    fun hasAliveTarget(run: Run): Boolean = run.members.any { run.alive(it) && Bukkit.getPlayer(it) != null }

    // --- 끝 -------------------------------------------------------------------------

    /** 성공·실패 — 곧 밖으로([end]). 두 번 불려도 한 번만. */
    fun finish(run: Run, success: Boolean, reason: String) {
        if (run.phase == Run.Phase.ENDING) return
        val now = System.currentTimeMillis()
        run.phase = Run.Phase.ENDING
        run.endingAt = now
        run.success = success
        run.choice = null
        d.holograms.clear(run)
        if (!success) MonsterAPI.get()?.killAllByTag(run.tag)
        val elapsed = run.timerStartedAt?.let { (now - it) / 1000 } ?: 0
        val wait = when {
            !success -> minOf(5, d.config.exitDelay)
            run.chests.values.any { it.boss } -> RoomFeatures.REWARD_WINDOW_SECONDS
            else -> d.config.exitDelay
        }
        run.closeAt = now + wait * 1000L
        val ph = Ph.of().dungeon(run.dungeon.name).difficulty(run.difficulty.name).amount(Durations.formatShort(elapsed))
            .value(d.messages.raw("fail-$reason")).count(wait)
        for (id in run.members) {
            val player = Bukkit.getPlayer(id) ?: continue
            if (success && id in run.downed) {
                run.downed.remove(id)
                player.gameMode = GameMode.ADVENTURE
                player.teleportAsync(run.current?.arrival ?: player.location)
            }
            player.showTitle(
                Title.title(
                    Text.renderFlat(if (success) "<gold><bold>던전 클리어!</bold></gold>" else "<red><bold>던전 실패</bold></red>"),
                    d.messages.component(if (success) "clear-subtitle" else "fail-$reason", ph),
                    TITLE_TIMES,
                ),
            )
            d.messages.send(player, if (success) "cleared" else "failed", ph)
            fireSignal(run, player, success, elapsed)
            if (success) {
                com.inmc.dungeon.player.Unlocks.record(id, run.dungeon.id, run.difficulty.id)
                recordClear(run, id, player, elapsed, now)
            }
        }
        // 접속이 끊긴 채(유예 중) 끝난 사람도 같이 깼다 — 기록만.
        if (success) for (id in run.members) if (Bukkit.getPlayer(id) == null) recordClear(run, id, null, elapsed, now)
        if (success) sound(run, Sound.UI_TOAST_CHALLENGE_COMPLETE)
    }

    /** 최단 클리어 순위 — 시간이 흐른 판만, 관리자 도구를 쓴 판은 빼고(`Run.assisted`). */
    private fun recordClear(run: Run, id: UUID, player: Player?, elapsed: Long, now: Long) {
        if (run.timerStartedAt == null) return
        if (run.assisted) {
            player?.let { d.messages.send(it, "rank-assisted") }
            return
        }
        val name = player?.name ?: Bukkit.getOfflinePlayer(id).name ?: return
        val result = d.records.record(run.dungeon.id, run.difficulty.id, id, name, elapsed, now)
        if (player == null) return
        val ph = Ph.of().difficulty(run.difficulty.name).amount(Durations.formatShort(elapsed)).value(Durations.formatShort(result.best)).count(result.rank ?: 0)
        d.messages.send(player, if (result.personalBest) "rank-new-best" else "rank-recorded", ph)
    }

    private fun fireRoomSignal(run: Run, player: Player, def: RoomDef) {
        InmcSignalEvent.fire(
            source = SIGNAL_SOURCE,
            type = "room",
            playerId = player.uniqueId,
            subject = def.kind.id,
            amount = 1,
            player = player,
        ) {
            mapOf(
                "dungeon" to run.dungeon.id,
                "difficulty" to run.difficulty.id,
                "room" to def.id,
                "step" to run.step.toString(),
            )
        }
    }

    private fun fireSignal(run: Run, player: Player, success: Boolean, elapsed: Long) {
        InmcSignalEvent.fire(
            source = SIGNAL_SOURCE,
            type = if (success) "clear" else "fail",
            playerId = player.uniqueId,
            subject = run.dungeon.id,
            amount = 1,
            player = player,
        ) {
            mapOf(
                "dungeon" to run.dungeon.id,
                "difficulty" to run.difficulty.id,
                "party" to run.startSize.toString(),
                "seconds" to elapsed.toString(),
                "rooms" to run.step.toString(),
            )
        }
    }

    /** 판을 닫는다 — 사람을 밖으로, 몬스터·방을 치우고, 다 비우면 칸을 돌려준다. */
    fun end(run: Run) {
        if (runs.remove(run.id) == null) return
        d.features.onLeave(run)
        for (id in run.members.toList()) sendOut(run, id)
        run.members.clear()
        MonsterAPI.get()?.killAllByTag(run.tag)
        d.holograms.clear(run)
        val rooms = listOfNotNull(run.current, run.next)
        if (rooms.isEmpty()) {
            cells.release(run.cell)
            return
        }
        var left = rooms.size
        for (placed in rooms) tidy(run, placed) { if (--left == 0) cells.release(run.cell) }
    }

    /** 판에서 한 사람 빼고 원래 자리로. 접속 중이 아니면 돌아갈 자리 파일이 남아 다음 접속 때 돌려보낸다. */
    private fun sendOut(run: Run, id: UUID) {
        byPlayer.remove(id)
        run.downed.remove(id)
        run.offline.remove(id)
        val player = Bukkit.getPlayer(id) ?: return
        RunBar.hide(run, player)
        restore(player)
    }

    /** 원래 자리·게임 모드로. 판 밖에서도 부른다(크러시 뒤 접속). */
    fun restore(player: Player) {
        val id = player.uniqueId
        d.effects.clear(player)
        d.tempItems.purge(player)
        val point = d.returns.get(id)
        player.gameMode = point?.gameMode ?: GameMode.SURVIVAL
        player.fireTicks = 0
        player.fallDistance = 0f
        player.maxHealth()?.let { player.health = it }
        val target = point?.location() ?: Bukkit.getWorlds().first().spawnLocation
        exiting.add(id)
        player.teleportAsync(target).whenComplete { _, _ -> Bukkit.getScheduler().runTask(d.plugin, Runnable { exiting.remove(id) }) }
        d.returns.forget(id)
    }

    /** `/던전 나가기` · 다른 길로 나갔을 때. [teleport] 가 false 면 이미 다른 곳으로 가는 중이라 순간이동하지 않는다. */
    fun leave(player: Player, teleport: Boolean = true) {
        val run = of(player.uniqueId) ?: return
        val id = player.uniqueId
        run.members.remove(id)
        run.choice?.ballot?.drop(id)
        if (teleport) sendOut(run, id) else {
            byPlayer.remove(id)
            run.downed.remove(id)
            RunBar.hide(run, player)
            d.effects.clear(player)
            d.tempItems.purge(player)
            player.gameMode = d.returns.get(id)?.gameMode ?: GameMode.SURVIVAL
            d.returns.forget(id)
        }
        d.messages.send(player, "left")
        afterLeave(run, id, player.name)
    }

    private fun afterLeave(run: Run, id: UUID, name: String) {
        if (run.members.isEmpty()) {
            if (run.phase != Run.Phase.ENDING) finish(run, success = false, reason = "abandoned")
            end(run)
            return
        }
        if (run.leader == id) {
            run.leader = run.members.first()
            tell(run, "leader-passed", Ph.of().player(Bukkit.getPlayer(run.leader)?.name ?: "?"))
        }
        tell(run, "member-left", Ph.of().player(name))
        if (run.phase != Run.Phase.ENDING && wiped(run)) finish(run, success = false, reason = "wiped")
    }

    // --- 접속 ---------------------------------------------------------------------

    fun onQuit(player: Player) {
        val run = of(player.uniqueId) ?: return
        run.offline[player.uniqueId] = System.currentTimeMillis()
        run.choice?.ballot?.drop(player.uniqueId)
        // 모두 끊겨도 바로 끝내지 않는다 — 유예가 지나면 [tick] 이 한 사람씩 빼고, 아무도 안 남으면 판을 닫는다.
        tell(run, "member-offline", Ph.of().player(player.name).value(d.config.reconnectGrace.toString()))
    }

    /** 다시 들어왔다 — 판이 살아 있으면 지금 방으로. */
    fun onJoin(player: Player): Boolean {
        val run = of(player.uniqueId) ?: return false
        run.offline.remove(player.uniqueId)
        if (player.uniqueId !in run.downed) run.choice?.ballot?.add(player.uniqueId)
        val placed = run.current
        if (placed != null) {
            player.gameMode = if (player.uniqueId in run.downed) GameMode.SPECTATOR else GameMode.ADVENTURE
            player.teleportAsync(placed.arrival)
        }
        RunBar.update(run, System.currentTimeMillis())
        d.messages.send(player, "reconnected")
        return true
    }

    // --- 1초마다 --------------------------------------------------------------------

    fun tick(now: Long) {
        for (run in runs.values.toList()) {
            try {
                tick(run, now)
            } catch (e: Exception) {
                d.logger.warning("판 ${run.id} 처리 중 오류: ${e.message}")
            }
        }
    }

    private fun tick(run: Run, now: Long) {
        if (run.phase == Run.Phase.ENDING) {
            // 보스 상자를 모두 열었으면 기다리지 않는다.
            val close = run.closeAt ?: now
            val soon = now + d.config.exitDelay * 1000L
            if (run.success && close > soon && run.chests.isNotEmpty() &&
                run.members.filter { Bukkit.getPlayer(it) != null }.all { id -> run.chests.values.all { id in it.opened } }
            ) {
                run.closeAt = soon
                tell(run, "exit-soon", Ph.of().count(d.config.exitDelay))
            }
            if (now >= (run.closeAt ?: now)) end(run)
            return
        }
        if (run.phase == Run.Phase.PREPARING) return
        if (run.timerStartedAt == null && now - run.waitingSince > d.config.startMaxWait * 1000L) {
            run.timerStartedAt = now
            tell(run, "timer-forced")
        }
        if (run.timerStartedAt != null && run.timeLeftMs(now) == 0L) {
            finish(run, success = false, reason = "timeout")
            return
        }
        for ((id, at) in run.downed.toList()) if (at != null && now >= at) revive(run, id)
        keepSpectating(run)
        for ((id, since) in run.offline.toList()) {
            if (now - since < d.config.reconnectGrace * 1000L) continue
            run.offline.remove(id)
            run.members.remove(id)
            byPlayer.remove(id)
            run.downed.remove(id)
            afterLeave(run, id, Bukkit.getOfflinePlayer(id).name ?: "?")
            if (runs[run.id] !== run) return
        }
        when (run.phase) {
            Run.Phase.CHOOSING -> tryDecide(run, now)
            Run.Phase.ROOM -> checkCleared(run)
            Run.Phase.DOOR_OPEN -> run.doorAt?.let { exit ->
                run.world.spawnParticle(Particle.PORTAL, exit.clone().add(0.0, 1.0, 0.0), 40, 0.4, 0.8, 0.4, 0.2)
                // 문이 열릴 때 이미 문 앞에 서 있던 사람은 움직이지 않으면 이동 사건이 안 온다 — 여기서도 본다.
                val someone = run.members.any { id -> run.alive(id) && Bukkit.getPlayer(id)?.let { near(it.location, exit) } == true }
                if (someone) transfer(run)
            }
            else -> Unit
        }
        if (runs[run.id] === run) RunBar.update(run, now)
    }

    /** 끌 때 — 모두를 그 자리에서 밖으로(워커·비동기 순간이동을 기다릴 수 없다). */
    fun shutdown() {
        for (run in runs.values.toList()) {
            MonsterAPI.get()?.killAllByTag(run.tag)
            d.holograms.clear(run)
            for (id in run.members) {
                val player = Bukkit.getPlayer(id) ?: continue
                RunBar.hide(run, player)
                d.effects.clear(player)
                d.tempItems.purge(player)
                val point = d.returns.get(id)
                player.gameMode = point?.gameMode ?: GameMode.SURVIVAL
                player.teleport(point?.location() ?: Bukkit.getWorlds().first().spawnLocation)
                d.returns.forgetNow(id)
            }
        }
        runs.clear()
        byPlayer.clear()
    }

    // --- 도움 -----------------------------------------------------------------------

    /** 방 하나를 치운다. */
    fun tidy(run: Run, placed: Run.Placed, then: () -> Unit) {
        val slot = placed.slot
        run.clearing.merge(slot, 1, Int::plus)
        clearArea(run.world, placed.min, placed.max) {
            val left = (run.clearing[slot] ?: 1) - 1
            if (left <= 0) run.clearing.remove(slot) else run.clearing[slot] = left
            then()
            if (left <= 0) run.afterClear.remove(slot)?.forEach { it() }
        }
    }

    /** 엔티티는 지금(메인), 블록은 워커에서 비운다. 방 시험도 쓴다. */
    fun clearArea(world: org.bukkit.World, min: Vec, max: Vec, then: () -> Unit) {
        val box = BoundingBox(min.x - 2.0, min.y - 2.0, min.z - 2.0, max.x + 3.0, max.y + 3.0, max.z + 3.0)
        for (entity in world.getNearbyEntities(box)) if (entity !is Player) entity.remove()
        d.schematics.clear(world, min, max) { error ->
            if (error != null) d.logger.warning("방 자리($min~$max)를 비우지 못했습니다 — $error")
            then()
        }
    }

    fun tell(run: Run, key: String, ph: Ph? = null) {
        for (id in run.members) Bukkit.getPlayer(id)?.let { d.messages.send(it, key, ph) }
    }

    private fun sound(run: Run, sound: Sound) {
        for (id in run.members) Bukkit.getPlayer(id)?.let { it.playSound(it.location, sound, 1f, 1f) }
    }

    private fun Player.maxHealth(): Double? = getAttribute(Attribute.MAX_HEALTH)?.value

    companion object {
        const val SIGNAL_SOURCE = "dungeon"

        /** 부활 직후 무적(밀리초). */
        const val REVIVE_GRACE_MS = 3000L
        /** 투표 중에 비밀 출구를 찾으면 늘려 주는 시간(초). */
        private const val SECRET_EXTRA_SECONDS = 10L

        private val TITLE_TIMES = Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(700))
    }
}
