package com.inmc.dungeon.gui

import com.inmc.dungeon.Dungeons
import com.inmc.dungeon.def.DifficultyMode
import com.inmc.dungeon.player.Unlocks
import com.inmc.dungeon.util.Ph
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

/**
 * 파티(로비) 화면 — 파티원 · [초대] · [모집 공지] · [난이도] · [입장] · [나가기]. 파티장만 초대·모집·난이도·입장·추방(쉬프트 클릭)을 한다.
 * 로비가 바뀌면 `Lobbies.refresh` 가 열린 이 화면을 다시 그린다.
 */
class LobbyMenu(d: Dungeons, viewer: Player, private val lobbyId: Int) :
    Menu(d, viewer, 54, "<dark_gray>던전 파티</dark_gray>") {

    override fun draw() {
        clear()
        val lobby = d.lobbies.get(lobbyId)
        if (lobby == null || viewer.uniqueId !in lobby.members) {
            set(SLOT_INFO, Icon.of(Material.BARRIER, "<red>파티가 없어졌습니다</red>"))
            close()
            return
        }
        val leader = lobby.leader == viewer.uniqueId
        val dungeon = lobby.dungeon
        set(
            SLOT_INFO,
            safeIcon(
                dungeon.icon, dungeon.name,
                listOf(
                    "<gray>난이도 ${lobby.difficulty.name}</gray>",
                    "<gray>인원 <white>${lobby.members.size}</white> / ${dungeon.party.min}~${dungeon.party.max}명</gray>",
                    "<dark_gray>파티 #${lobby.id}</dark_gray>",
                ),
            ),
        )

        lobby.members.take(MEMBER_SLOTS.size).forEachIndexed { i, id ->
            val name = d.lobbies.nameOf(id)
            val lore = buildList {
                if (id == lobby.leader) add("<gold>★ 파티장</gold>")
                if (Bukkit.getPlayer(id) == null) add("<red>접속 안 함</red>")
                if (leader && id != lobby.leader) add("<gray>쉬프트 클릭: 내보내기</gray>")
            }
            set(MEMBER_SLOTS[i], head(id, "<white>$name</white>", lore)) { event ->
                if (!leader || id == lobby.leader || !event.isShiftClick) return@set
                d.lobbies.kick(lobby, viewer, id)?.let { d.messages.send(viewer, it) }
                refresh()
            }
        }

        if (leader) {
            set(SLOT_INVITE, Icon.of(Material.WRITABLE_BOOK, "<aqua>초대</aqua>", "<gray>이름을 적어 초대합니다.</gray>", "", "<yellow>클릭</yellow>")) {
                ask(DialogForm("파티 초대").text("name", "플레이어 이름", maxLength = 16)) { values ->
                    val target = Bukkit.getPlayerExact(values.text("name").trim())
                    if (target == null) d.messages.send(viewer, "party-target-offline")
                    else d.lobbies.invite(lobby, viewer, target)?.let { d.messages.send(viewer, it, Ph.of().player(target.name)) }
                }
            }
            set(SLOT_RECRUIT, Icon.of(Material.BELL, "<yellow>모집 공지</yellow>", "<gray>서버 전체에 [참여하기] 를 띄웁니다.</gray>", "<gray>포탈까지 오지 않아도 함께 들어갑니다.</gray>", "", "<yellow>클릭</yellow>")) {
                val error = d.lobbies.recruit(lobby, viewer)
                d.messages.send(viewer, error ?: "party-recruited", Ph.of().value(d.config.recruitCooldown.toString()))
            }
            if (dungeon.difficultyMode == DifficultyMode.CHOOSE) {
                val options = dungeon.difficulties.filter { Unlocks.allowed(viewer.uniqueId, dungeon, it) }
                set(SLOT_DIFFICULTY, Icon.of(Material.COMPARATOR, "<gold>난이도: </gold>${lobby.difficulty.name}", "<gray>좌클릭 다음 · 우클릭 이전</gray>", "<dark_gray>깬 난이도의 다음 단계까지</dark_gray>")) { event ->
                    if (options.isEmpty()) return@set
                    val index = options.indexOf(lobby.difficulty).coerceAtLeast(0)
                    val next = options[Math.floorMod(index + if (event.isRightClick) -1 else 1, options.size)]
                    d.lobbies.setDifficulty(lobby, viewer, next)?.let { d.messages.send(viewer, it) }
                }
            }
            val ready = lobby.members.size >= dungeon.party.min
            set(
                SLOT_START,
                Icon.of(
                    if (lobby.queuedAt != null) Material.CLOCK else if (ready) Material.LIME_CONCRETE else Material.GRAY_CONCRETE,
                    if (lobby.queuedAt != null) "<yellow>자리를 기다리는 중…</yellow>" else "<green>입장</green>",
                    buildList {
                        add("<gray>인원 ${lobby.members.size} / ${dungeon.party.min}~${dungeon.party.max}명</gray>")
                        if (!ready) add("<red>${dungeon.party.min}명이 모여야 합니다</red>")
                        if (lobby.queuedAt != null) add("<gray>열린 던전이 가득 찼습니다 — 자리가 나면 저절로 들어갑니다.</gray>")
                        add("")
                        add("<yellow>클릭</yellow>")
                    },
                ),
            ) {
                val error = d.lobbies.start(lobby, viewer)
                if (error == null) return@set
                if (error.isNotEmpty()) d.messages.send(viewer, error, Ph.of().count(dungeon.party.min).amount(dungeon.party.max.toString()))
                if (d.lobbies.get(lobbyId) != null) refresh()
            }
        } else {
            set(SLOT_START, Icon.of(Material.CLOCK, "<gray>파티장이 입장하기를 기다립니다</gray>", "<gray>입장하면 어디에 있든 함께 옮겨집니다.</gray>"))
        }

        set(SLOT_LEAVE, Icon.of(Material.OAK_DOOR, "<red>파티에서 나가기</red>", if (leader) "<gray>파티장은 다음 사람에게 넘어갑니다.</gray>" else "")) {
            d.lobbies.leave(viewer)
            viewer.closeInventory()
        }
        close()
    }

    private fun head(id: java.util.UUID, name: String, lore: List<String>): ItemStack {
        val stack = ItemStack(Material.PLAYER_HEAD)
        stack.editMeta(SkullMeta::class.java) { it.owningPlayer = Bukkit.getOfflinePlayer(id) }
        return Icon.annotate(stack, name, lore)
    }

    private companion object {
        const val SLOT_INFO = 4
        const val SLOT_INVITE = 37
        const val SLOT_RECRUIT = 39
        const val SLOT_DIFFICULTY = 41
        const val SLOT_START = 43
        const val SLOT_LEAVE = 49
        val MEMBER_SLOTS = listOf(19, 20, 21, 22, 23, 24, 25)
    }
}
