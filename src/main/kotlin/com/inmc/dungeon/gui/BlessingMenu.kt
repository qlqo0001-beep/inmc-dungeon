package com.inmc.dungeon.gui

import com.inmc.dungeon.Dungeons
import kr.inmc.core.gui.Icon
import org.bukkit.Material
import org.bukkit.entity.Player

/** 축복방 — 셋 가운데 하나(사람마다, 판 동안 유지). 닫아도 제단을 다시 누르면 같은 셋이 나온다. */
class BlessingMenu(d: Dungeons, viewer: Player, private val runId: Int, private val offers: List<String>) :
    Menu(d, viewer, 27, "<dark_gray>축복을 하나 고르세요</dark_gray>") {

    override fun draw() {
        clear()
        offers.take(SLOTS.size).forEachIndexed { i, id ->
            val buff = d.effects.buffs[id] ?: return@forEachIndexed
            set(SLOTS[i], safeIcon(buff.icon, buff.name, buff.description + buff.lines() + listOf("", "<yellow>클릭해서 받기</yellow>"))) {
                val run = d.runs.of(viewer.uniqueId)?.takeIf { it.id == runId } ?: return@set viewer.closeInventory()
                d.features.bless(run, viewer, id)
                viewer.closeInventory()
            }
        }
        set(SLOT_CLOSE_SMALL, Icon.of(Material.BARRIER, "<gray>나중에 고르기</gray>", "<dark_gray>제단을 다시 누르면 열립니다</dark_gray>")) { viewer.closeInventory() }
    }

    private companion object {
        val SLOTS = listOf(11, 13, 15)
        const val SLOT_CLOSE_SMALL = 26
    }
}
