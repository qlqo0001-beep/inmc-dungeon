package com.inmc.dungeon.party

import com.inmc.dungeon.Dungeons
import com.inmc.dungeon.def.Difficulty
import com.inmc.dungeon.def.DungeonDef
import com.inmc.dungeon.gui.LobbyMenu
import com.inmc.dungeon.player.Unlocks
import com.inmc.dungeon.util.Ph
import kr.inmc.core.integration.TitleForgeNames
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 로비(입장 전 파티) 전부. 만들기 · 초대(채팅 클릭 수락) · 모집 공지([참여하기] — 포탈까지 오지 않아도 된다) · 나가기 · 추방 · 입장 · 대기열 · 시간 초과.
 * **메인 스레드에서만.** 바뀌면 열려 있는 로비 화면을 다시 그린다.
 */
class Lobbies(private val d: Dungeons) {

    private val lobbies = LinkedHashMap<Int, Lobby>()
    private val byPlayer = HashMap<UUID, Lobby>()
    private var nextId = 1

    fun of(player: UUID): Lobby? = byPlayer[player]

    fun get(id: Int): Lobby? = lobbies[id]

    fun all(): Collection<Lobby> = lobbies.values

    /** [leader] 를 파티장으로 새 로비. 다른 로비에 있었으면 그 로비에서 나온다. */
    fun create(leader: Player, dungeon: DungeonDef, difficulty: Difficulty, portal: Int?): Lobby {
        leave(leader, quiet = true)
        val lobby = Lobby(nextId++, leader.uniqueId, dungeon, difficulty, portal, System.currentTimeMillis())
        lobbies[lobby.id] = lobby
        byPlayer[leader.uniqueId] = lobby
        return lobby
    }

    /** 안 되면 메시지 키. */
    fun invite(lobby: Lobby, inviter: Player, target: Player): String? {
        if (lobby.leader != inviter.uniqueId) return "party-not-leader"
        if (target.uniqueId in lobby.members) return "party-already-member"
        if (lobby.full) return "party-full"
        if (d.runs.of(target.uniqueId) != null) return "party-target-busy"
        lobby.invites[target.uniqueId] = System.currentTimeMillis() + d.config.inviteSeconds * 1000L
        d.messages.send(target, "party-invited", ph(lobby).player(name(inviter)).value(d.config.inviteSeconds.toString()))
        tell(lobby, "party-invite-sent", ph(lobby).player(name(target)))
        return null
    }

    fun accept(player: Player, lobbyId: Int): String? {
        val lobby = lobbies[lobbyId] ?: return "party-gone"
        val until = lobby.invites.remove(player.uniqueId) ?: return "party-no-invite"
        if (until < System.currentTimeMillis()) return "party-no-invite"
        return add(lobby, player)
    }

    fun decline(player: Player, lobbyId: Int) {
        val lobby = lobbies[lobbyId] ?: return
        if (lobby.invites.remove(player.uniqueId) != null) tell(lobby, "party-declined", ph(lobby).player(name(player)))
    }

    /** 모집 공지의 [참여하기]. */
    fun join(player: Player, lobbyId: Int): String? {
        val lobby = lobbies[lobbyId] ?: return "party-gone"
        return add(lobby, player)
    }

    private fun add(lobby: Lobby, player: Player): String? {
        if (player.uniqueId in lobby.members) return "party-already-member"
        if (lobby.full) return "party-full"
        if (d.runs.of(player.uniqueId) != null) return "already-in-run"
        if (!player.hasPermission(Dungeons.PLAY)) return "no-permission"
        leave(player, quiet = true)
        lobby.members.add(player.uniqueId)
        byPlayer[player.uniqueId] = lobby
        tell(lobby, "party-joined", ph(lobby).player(name(player)).count(lobby.members.size).amount(lobby.dungeon.party.max.toString()))
        refresh(lobby)
        return null
    }

    /** 로비에서 나온다. 파티장이 나가면 다음 사람에게 넘기고, 아무도 없으면 해체. */
    fun leave(player: Player, quiet: Boolean = false) {
        val lobby = byPlayer.remove(player.uniqueId) ?: return
        lobby.members.remove(player.uniqueId)
        if (!quiet) d.messages.send(player, "party-left-self")
        if (lobby.members.isEmpty()) {
            lobbies.remove(lobby.id)
            return
        }
        if (lobby.leader == player.uniqueId) {
            lobby.leader = lobby.members.first()
            tell(lobby, "party-leader-now", ph(lobby).player(nameOf(lobby.leader)))
        }
        tell(lobby, "party-member-left", ph(lobby).player(name(player)))
        refresh(lobby)
    }

    fun kick(lobby: Lobby, by: Player, target: UUID): String? {
        if (lobby.leader != by.uniqueId) return "party-not-leader"
        if (target == by.uniqueId || target !in lobby.members) return "party-not-member"
        lobby.members.remove(target)
        byPlayer.remove(target)
        Bukkit.getPlayer(target)?.let { d.messages.send(it, "party-kicked", ph(lobby)) }
        tell(lobby, "party-member-left", ph(lobby).player(nameOf(target)))
        refresh(lobby)
        return null
    }

    /** 서버 전체에 모집 공지 — [참여하기] 를 누르면 이 로비로. */
    fun recruit(lobby: Lobby, by: Player): String? {
        if (lobby.leader != by.uniqueId) return "party-not-leader"
        if (lobby.full) return "party-full"
        val now = System.currentTimeMillis()
        val wait = lobby.lastRecruit + d.config.recruitCooldown * 1000L - now
        if (wait > 0) return "party-recruit-cooldown"
        lobby.lastRecruit = now
        val text = d.messages.raw("party-recruit")
        val line = Text.renderFlat(
            d.messages.raw(kr.inmc.core.config.MessageCatalog.PREFIX) +
                ph(lobby).player(name(by)).count(lobby.members.size).amount(lobby.dungeon.party.max.toString()).apply(text),
        )
        for (player in Bukkit.getOnlinePlayers()) {
            if (d.runs.of(player.uniqueId) != null) continue
            player.sendMessage(line)
        }
        return null
    }

    fun setDifficulty(lobby: Lobby, by: Player, difficulty: Difficulty): String? {
        if (lobby.leader != by.uniqueId) return "party-not-leader"
        if (!Unlocks.allowed(by.uniqueId, lobby.dungeon, difficulty)) return "difficulty-locked"
        lobby.difficulty = difficulty
        refresh(lobby)
        return null
    }

    /** 파티장이 입장. 판이 가득 찼으면 대기열에 넣는다(자리가 나면 저절로). 안 되면 메시지 키. */
    fun start(lobby: Lobby, by: Player): String? {
        if (lobby.leader != by.uniqueId) return "party-not-leader"
        val players = lobby.members.mapNotNull(Bukkit::getPlayer).filter { d.runs.of(it.uniqueId) == null }
        if (players.size < lobby.dungeon.party.min) return "party-too-few"
        if (players.size > lobby.dungeon.party.max) return "party-full"
        if (!Unlocks.allowed(lobby.leader, lobby.dungeon, lobby.difficulty)) return "difficulty-locked"
        return launch(lobby, players)
    }

    private fun launch(lobby: Lobby, players: List<Player>): String? {
        val leader = Bukkit.getPlayer(lobby.leader) ?: players.first()
        when (val error = d.runs.start(lobby.dungeon, lobby.difficulty, players, leader)) {
            null -> {
                disband(lobby, null)
                return null
            }
            "full" -> {
                if (lobby.queuedAt == null) lobby.queuedAt = System.currentTimeMillis()
                tell(lobby, "party-queued", ph(lobby).count(d.runs.cells.activeCount).amount(d.runs.cells.max.toString()))
                refresh(lobby)
                return ""
            }
            else -> return error
        }
    }

    /** 로비를 없앤다. [reasonKey] 가 있으면 남은 사람에게 알린다. */
    fun disband(lobby: Lobby, reasonKey: String?) {
        lobbies.remove(lobby.id) ?: return
        for (id in lobby.members) {
            if (byPlayer[id] === lobby) byPlayer.remove(id)
            val player = Bukkit.getPlayer(id) ?: continue
            if (reasonKey != null) d.messages.send(player, reasonKey, ph(lobby))
            if (player.openInventory.topInventory.holder is LobbyMenu) player.closeInventory()
        }
    }

    /** 1초마다 — 초대 만료 · 시간 초과 · 대기열. */
    fun tick(now: Long) {
        for (lobby in lobbies.values.toList()) {
            lobby.invites.entries.removeIf { it.value < now }
            if (lobby.queuedAt == null && now - lobby.createdAt > d.config.lobbyTimeout * 1000L) {
                disband(lobby, "party-timeout")
                continue
            }
        }
        // 자리가 나면 가장 오래 기다린 로비부터.
        val waiting = lobbies.values.filter { it.queuedAt != null }.sortedBy { it.queuedAt }
        for (lobby in waiting) {
            if (d.runs.cells.activeCount >= d.runs.cells.max) break
            val players = lobby.members.mapNotNull(Bukkit::getPlayer).filter { d.runs.of(it.uniqueId) == null }
            if (players.size < lobby.dungeon.party.min) continue
            tell(lobby, "party-queue-ready", ph(lobby))
            launch(lobby, players)
        }
    }

    /** 접속이 끊기면 로비에서 뺀다. */
    fun onQuit(player: Player) {
        if (byPlayer.containsKey(player.uniqueId)) leave(player, quiet = true)
    }

    /** 판에 들어간 사람(관리자 시작 등)은 로비에서 뺀다. */
    fun forget(player: UUID) {
        val lobby = byPlayer.remove(player) ?: return
        lobby.members.remove(player)
        if (lobby.members.isEmpty()) lobbies.remove(lobby.id)
    }

    fun ph(lobby: Lobby): Ph = Ph.of().dungeon(lobby.dungeon.name).difficulty(lobby.difficulty.name).id(lobby.id.toString())

    fun tell(lobby: Lobby, key: String, ph: Ph) {
        for (id in lobby.members) Bukkit.getPlayer(id)?.let { d.messages.send(it, key, ph) }
    }

    /** 열려 있는 로비 화면을 다시 그린다. */
    fun refresh(lobby: Lobby) {
        for (id in lobby.members) {
            val player = Bukkit.getPlayer(id) ?: continue
            val menu = player.openInventory.topInventory.holder as? LobbyMenu ?: continue
            menu.refresh()
        }
    }

    fun name(player: Player): String = TitleForgeNames.displayName(player.uniqueId, player.name)

    fun nameOf(id: UUID): String = Bukkit.getPlayer(id)?.let(::name) ?: Bukkit.getOfflinePlayer(id).name ?: "?"
}
