package com.inmc.dungeon.gui

import com.inmc.dungeon.Dungeons
import com.inmc.dungeon.def.Difficulty
import com.inmc.dungeon.def.DifficultyMode
import com.inmc.dungeon.def.DungeonDef
import com.inmc.dungeon.player.Unlocks
import com.inmc.dungeon.util.Ph
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Durations
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * 던전 입장 화면(사양 3절) — 포탈에 들어서면 열린다. 던전 정보 · 난이도(`choose` 면 고르기, 잠긴 단계는 자물쇠) · [솔로 입장] · [파티 만들기].
 */
class EntryMenu(
    d: Dungeons,
    viewer: Player,
    private val dungeon: DungeonDef,
    initial: Difficulty,
    private val portal: Int?,
) : Menu(d, viewer, 54, "<dark_gray>던전 입장 — </dark_gray>${dungeon.name}") {

    private var selected: Difficulty = initial

    override fun draw() {
        clear()
        set(SLOT_INFO, safeIcon(dungeon.icon, dungeon.name, info()))

        val choosing = dungeon.difficultyMode == DifficultyMode.CHOOSE
        val shown = if (choosing) dungeon.difficulties.take(DIFFICULTY_SLOTS.size) else listOf(selected)
        val slots = if (choosing) DIFFICULTY_SLOTS.take(shown.size) else listOf(SLOT_FIXED_DIFFICULTY)
        shown.forEachIndexed { i, difficulty ->
            val allowed = Unlocks.allowed(viewer.uniqueId, dungeon, difficulty)
            set(slots[i], difficultyIcon(difficulty, allowed, choosing)) {
                if (!choosing) return@set
                if (!allowed) {
                    d.messages.send(viewer, "difficulty-locked", Ph.of().difficulty(dungeon.previous(difficulty)?.name ?: ""))
                    return@set
                }
                selected = difficulty
                refresh()
            }
        }

        if (dungeon.party.min <= 1) {
            set(SLOT_SOLO, Icon.of(Material.IRON_SWORD, "<green>솔로 입장</green>", "<gray>혼자 들어갑니다.</gray>", "", "<yellow>클릭</yellow>")) {
                val lobby = d.lobbies.create(viewer, dungeon, selected, portal)
                val error = d.lobbies.start(lobby, viewer)
                if (error == null) return@set
                if (error.isNotEmpty()) {
                    d.lobbies.disband(lobby, null)
                    d.messages.send(viewer, error, Ph.of().count(dungeon.party.min).amount(dungeon.party.max.toString()))
                }
                viewer.closeInventory()
            }
        }
        val mine = d.lobbies.of(viewer.uniqueId)
        set(
            SLOT_PARTY,
            if (mine != null) Icon.of(Material.PLAYER_HEAD, "<aqua>내 파티 화면</aqua>", "<gray>${mine.dungeon.name} · ${mine.members.size}명</gray>")
            else Icon.of(Material.PLAYER_HEAD, "<aqua>파티 만들기</aqua>", "<gray>${dungeon.party.min}~${dungeon.party.max}명 · 초대하거나 모집 공지를 띄웁니다.</gray>", "", "<yellow>클릭</yellow>"),
        ) {
            val lobby = mine ?: d.lobbies.create(viewer, dungeon, selected, portal)
            LobbyMenu(d, viewer, lobby.id).show()
        }
        close()
    }

    private fun info(): List<String> = buildList {
        add("<dark_gray>테마 ${dungeon.theme.ifBlank { "-" }}</dark_gray>")
        dungeon.description.forEach(::add)
        add("")
        if (dungeon.estimated.isNotBlank()) add("<gray>예상 시간 <white>${dungeon.estimated}</white></gray>")
        add("<gray>인원 <white>${dungeon.party.min}~${dungeon.party.max}명</white></gray>")
        add("<gray>방 <white>${dungeon.length.first}~${dungeon.length.last}개</white> 뒤 보스</gray>")
        if (dungeon.rewardsText.isNotEmpty()) {
            add("")
            add("<gold>주요 보상</gold>")
            dungeon.rewardsText.forEach(::add)
        }
        add("")
        add(if (dungeon.difficultyMode == DifficultyMode.PORTAL) "<gray>이 포탈의 난이도는 정해져 있습니다.</gray>" else "<gray>아래에서 난이도를 고르세요.</gray>")
    }

    private fun difficultyIcon(difficulty: Difficulty, allowed: Boolean, choosing: Boolean) = Icon.of(
        when {
            !allowed -> Material.BARRIER
            difficulty == selected -> Material.LIME_DYE
            else -> Material.GRAY_DYE
        },
        (if (difficulty == selected) "<green>▶ </green>" else "") + difficulty.name,
        buildList {
            add("<gray>제한시간 <white>${if (difficulty.timeLimit <= 0) "없음" else Durations.formatShort(difficulty.timeLimit.toLong())}</white></gray>")
            add("<gray>부활 <white>${difficulty.revives}회</white> · 대기 <white>${difficulty.reviveDelay}초</white></gray>")
            add("<gray>몬스터 수 <white>×${difficulty.mobCount}</white> · 체력 <white>×${difficulty.mobHealth}</white> · 피해 <white>×${difficulty.mobDamage}</white></gray>")
            if (difficulty.rewardMultiplier != 1.0) add("<gold>보상 ×${difficulty.rewardMultiplier}</gold>")
            d.records.top(dungeon.id, difficulty.id, 1).firstOrNull()?.let { add("<gray>최단 기록 <white>${Durations.formatShort(it.best)}</white> — ${it.name}</gray>") }
            d.records.entryOf(dungeon.id, difficulty.id, viewer.uniqueId)?.let { add("<gray>내 기록 <white>${Durations.formatShort(it.best)}</white></gray>") }
            add("")
            when {
                !allowed -> add("<red>잠김 — ${dungeon.previous(difficulty)?.name ?: ""}<red> 을(를) 깨면 열립니다</red>")
                !choosing -> Unit
                difficulty == selected -> add("<green>고른 난이도</green>")
                else -> add("<yellow>클릭해서 고르기</yellow>")
            }
        },
    )

    private companion object {
        const val SLOT_INFO = 4
        const val SLOT_FIXED_DIFFICULTY = 22
        const val SLOT_SOLO = 38
        const val SLOT_PARTY = 42
        val DIFFICULTY_SLOTS = listOf(19, 20, 21, 22, 23, 24, 25)
    }
}
