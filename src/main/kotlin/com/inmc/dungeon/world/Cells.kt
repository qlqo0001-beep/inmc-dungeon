package com.inmc.dungeon.world

import java.util.TreeSet

/**
 * 던전 월드의 칸 — 판 하나가 칸 하나. 동시에 열 수 있는 판 수([max])가 곧 칸 수다(사양 5절 `max-active-dungeons`).
 * 순수 계산이라 서버 없이 테스트한다(`CellsTest`).
 *
 * 칸 안에는 방 자리가 둘(0·1) — 지금 방과 다음 방. 파티는 문을 지나면 다 같이 옮겨 가므로 방이 셋 이상 서 있을 일이 없다.
 * 옮긴 뒤 지난 방을 비우고 그 자리를 다음 다음 방이 쓴다.
 */
class Cells(max: Int) {

    @Volatile
    var max: Int = max
        set(value) {
            field = value.coerceAtLeast(1)
        }

    private val used = TreeSet<Int>()

    val activeCount: Int get() = used.size

    /** 비어 있는 가장 작은 칸. 다 찼으면 null. */
    fun acquire(): Int? {
        var index = 0
        while (index in used) index++
        if (index >= max) return null
        used.add(index)
        return index
    }

    fun release(index: Int) {
        used.remove(index)
    }

    companion object {

        /** 칸·자리의 가장 작은 모서리(블록 좌표). 칸은 z 로, 자리는 x 로 늘어선다 — 0 근처(월드 스폰)는 비워 둔다. */
        fun origin(cell: Int, slot: Int, cellSpacing: Int, slotSpacing: Int, baseY: Int): Triple<Int, Int, Int> =
            Triple(slot * slotSpacing, baseY, (cell + 1) * cellSpacing)
    }
}
