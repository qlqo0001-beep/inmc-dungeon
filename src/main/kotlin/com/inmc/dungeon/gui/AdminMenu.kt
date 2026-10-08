package com.inmc.dungeon.gui

import com.inmc.dungeon.DungeonPlugin
import com.inmc.dungeon.Dungeons
import com.inmc.dungeon.run.Run
import com.inmc.dungeon.util.Ph
import com.inmc.dungeon.verify.Verifier
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.PickMenu
import kr.inmc.core.util.Durations
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * `/던전 관리 화면` — 어드민 허브(`/메뉴 어드민`)에서 오는 관리 화면(2026-10-08). 명령어 스물 몇 개를 외우지 않아도 되게:
 * 열린 판(끝내기) · 던전(입장 화면) · 방 붙여 보기 · 검증 · 리로드 · 순위. 선택 영역이 필요한 것(방 저장·포탈 저장)은 명령어 그대로다.
 */
class AdminMenu(d: Dungeons, viewer: Player) : Menu(d, viewer, 54, "<dark_gray>던전 관리</dark_gray>") {

    override fun draw() {
        clear()
        val now = System.currentTimeMillis()
        val runs = d.runs.all().toList()
        set(SLOT_RUNS_LABEL, Icon.of(Material.CLOCK, "<yellow>열린 판 <white>${runs.size}</white>/${d.runs.cells.max}</yellow>", listOf("<gray>오른쪽 칸을 누르면 그 판을 끝냅니다(확인 뒤).</gray>")))
        runs.take(RUN_SLOTS.size).forEachIndexed { i, run -> set(RUN_SLOTS[i], runIcon(run, now)) { confirmEnd(run) } }

        set(SLOT_DUNGEONS_LABEL, Icon.of(Material.ENDER_EYE, "<light_purple>던전 <white>${d.library.dungeons.size}</white></light_purple>", listOf("<gray>오른쪽 칸을 누르면 입장 화면(포탈 없이).</gray>")))
        d.library.dungeons.values.take(DUNGEON_SLOTS.size).forEachIndexed { i, dungeon ->
            set(
                DUNGEON_SLOTS[i],
                safeIcon(
                    dungeon.icon, dungeon.name,
                    listOf(
                        "<gray>id <white>${dungeon.id}</white> · 테마 <white>${dungeon.theme.ifBlank { "-" }}</white></gray>",
                        "<gray>난이도 <white>${dungeon.difficulties.joinToString(" · ") { it.name }}</white></gray>",
                        "<gray>방 풀 <white>${d.library.pool(dungeon).size}</white>개" + (if (dungeon.enabled) "" else " · <red>꺼짐</red>") + "</gray>",
                        "", "<yellow>▶ 클릭: 입장 화면</yellow>",
                    ),
                ),
            ) { EntryMenu(d, viewer, dungeon, d.portals.pickDifficulty(dungeon), null).show() }
        }

        set(SLOT_ROOMS, Icon.of(Material.STRUCTURE_BLOCK, "<aqua>방 붙여 보기 <white>${d.library.rooms.size}</white></aqua>", listOf("<gray>방 하나를 빈 칸에 붙이고 표지판 자리를 글자로 보여 줍니다.</gray>", "<gray>끝은 /던전 나가기.</gray>") + Editors.pickHint)) { pickRoom() }
        set(SLOT_VERIFY, Icon.of(Material.COMPARATOR, "<green>검증</green>", listOf("<gray>정의·방·보상 상자·축복을 검사합니다(채팅으로).</gray>"))) { Verifier(d).run(viewer) }
        set(SLOT_RELOAD, Icon.of(Material.REPEATER, "<yellow>리로드</yellow>", listOf("<gray>설정·메시지·정의를 다시 읽습니다. 열린 판은 그대로 갑니다.</gray>"))) {
            viewer.closeInventory()
            (d.plugin as DungeonPlugin).reload { d.messages.send(viewer, "reloaded", Ph.of().count(d.library.dungeons.size).amount(d.library.rooms.size.toString())) }
        }
        set(SLOT_RANK, Icon.of(Material.GOLD_INGOT, "<gold>최단 클리어 순위</gold>", listOf("<gray>/던전 순위</gray>"))) {
            viewer.closeInventory()
            viewer.performCommand("던전 순위")
        }
        set(SLOT_HUB, Icon.of(Material.COMPASS, "<gray>◀ 어드민 메뉴로</gray>")) { viewer.performCommand("메뉴 어드민") }
        fillEmpty(Icon.EDGE)
        close()
    }

    private fun runIcon(run: Run, now: Long) = Icon.of(
        Material.PLAYER_HEAD,
        "<yellow>판 #${run.id} — ${run.dungeon.name}</yellow>",
        listOf(
            "<gray>난이도 <white>${run.difficulty.name}</white> · 방 <white>${run.step}</white> · <white>${run.phase.name.lowercase()}</white></gray>",
            "<gray>사람 <white>" + run.members.joinToString(", ") { Bukkit.getOfflinePlayer(it).name ?: "?" } + "</white></gray>",
            "<gray>남은 시간 <white>" + (run.timeLeftMs(now)?.let { Durations.formatShort(it / 1000) } ?: "없음") + "</white></gray>",
            "", "<red>▶ 클릭: 끝내기</red>",
        ),
    )

    private fun confirmEnd(run: Run) {
        ConfirmMenu(
            d, "판 #${run.id} 을(를) 끝낼까요?", listOf("<gray>사람들은 들어가기 전 자리로 돌아갑니다.</gray>"),
            onConfirm = {
                d.runs.finish(run, success = false, reason = "admin")
                d.runs.end(run)
                d.messages.send(viewer, "admin-ended", Ph.of().id(run.id.toString()))
                show()
            },
            onCancel = { show() },
        ).open(viewer)
    }

    private fun pickRoom() {
        val rooms = d.library.rooms.values.sortedWith(compareBy({ it.kind.ordinal }, { it.id }))
        PickMenu(
            d, viewer, "방 고르기 — 붙여 보기", rooms,
            icon = { room -> safeIcon(room.kind.icon, room.displayName, listOf("<gray>${room.id} · ${room.kind.label} · 테마 ${room.theme.ifBlank { "-" }}</gray>", "<gray>크기 ${room.size}</gray>")) },
            back = { show() },
        ) { room ->
            viewer.closeInventory()
            d.preview.start(viewer, room)?.let { d.messages.send(viewer, it, Ph.of().count(d.runs.cells.max)) }
        }.show()
    }

    private companion object {
        const val SLOT_RUNS_LABEL = 0
        val RUN_SLOTS = (1..8).toList()
        const val SLOT_DUNGEONS_LABEL = 9
        val DUNGEON_SLOTS = (10..17).toList()
        const val SLOT_ROOMS = 45
        const val SLOT_VERIFY = 47
        const val SLOT_RELOAD = 49
        const val SLOT_RANK = 50
        const val SLOT_HUB = 51
    }
}
