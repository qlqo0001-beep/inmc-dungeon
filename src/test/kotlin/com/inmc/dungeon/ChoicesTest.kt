package com.inmc.dungeon

import com.inmc.dungeon.def.DungeonDef
import com.inmc.dungeon.def.RoomDef
import com.inmc.dungeon.def.RoomKind
import com.inmc.dungeon.run.Choices
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChoicesTest {

    private val all = setOf(RoomKind.START, RoomKind.COMBAT, RoomKind.ELITE, RoomKind.EVENT, RoomKind.TREASURE, RoomKind.REST, RoomKind.BOSS)

    private fun input(step: Int, used: Map<RoomKind, Int> = emptyMap(), slots: Int = 3, available: Set<RoomKind> = all) = Choices.Input(
        step = step, length = 3..6, weights = DungeonDef.DEFAULT_WEIGHTS, used = used,
        maxPerKind = mapOf(RoomKind.TREASURE to 1), eliteFrom = 2, available = available, slots = slots,
    )

    @Test
    fun `최소 전에는 보스가 안 나오고 최대에서는 보스만`() {
        repeat(200) { seed ->
            assertFalse(RoomKind.BOSS in Choices.kinds(input(step = 2), Random(seed)))
            assertEquals(listOf(RoomKind.BOSS), Choices.kinds(input(step = 6), Random(seed)))
        }
    }

    @Test
    fun `최소를 지나면 보스가 섞이고 갈수록 자주`() {
        fun rate(step: Int) = (0 until 2000).count { RoomKind.BOSS in Choices.kinds(input(step), Random(it)) } / 2000.0
        val atMin = rate(3)
        val later = rate(5)
        assertTrue(atMin > 0.15 && atMin < 0.35, "최소에서 약 1/4: $atMin")
        assertTrue(later > atMin, "뒤로 갈수록 자주: $atMin → $later")
        assertEquals(0.25, Choices.bossChance(3, 3..6))
        assertEquals(1.0, Choices.bossChance(6, 3..6))
        assertEquals(0.0, Choices.bossChance(2, 3..6))
    }

    @Test
    fun `보스가 섞이면 마지막 칸이고 선택지 수는 출구 수`() {
        repeat(500) { seed ->
            val kinds = Choices.kinds(input(step = 4), Random(seed))
            assertEquals(3, kinds.size)
            if (RoomKind.BOSS in kinds) assertEquals(RoomKind.BOSS, kinds.last())
        }
        assertEquals(2, Choices.kinds(input(step = 0, slots = 2), Random(1)).size)
    }

    @Test
    fun `정예는 정한 번째 방부터 · 최대 수를 넘지 않는다 · 풀에 없는 종류는 안 나온다`() {
        repeat(300) { seed ->
            assertFalse(RoomKind.ELITE in Choices.kinds(input(step = 0), Random(seed)), "첫 방(1번째)에는 정예 없음")
            assertFalse(RoomKind.TREASURE in Choices.kinds(input(step = 1, used = mapOf(RoomKind.TREASURE to 1)), Random(seed)))
            assertFalse(RoomKind.SHOP in Choices.kinds(input(step = 1), Random(seed)), "상점방은 풀에 없다")
        }
    }

    @Test
    fun `종류가 겹치지 않게 먼저 · 모자라면 같은 종류를 또`() {
        repeat(300) { seed ->
            val kinds = Choices.kinds(input(step = 0), Random(seed))
            assertEquals(kinds.size, kinds.toSet().size, "종류가 넉넉하면 겹치지 않는다: $kinds")
        }
        val onlyCombat = Choices.kinds(input(step = 0, available = setOf(RoomKind.COMBAT, RoomKind.BOSS)), Random(3))
        assertEquals(listOf(RoomKind.COMBAT, RoomKind.COMBAT, RoomKind.COMBAT), onlyCombat)
    }

    @Test
    fun `같은 시드면 같은 선택지`() {
        val a = (0..5).map { Choices.kinds(input(step = it), Random(42 + it)) }
        val b = (0..5).map { Choices.kinds(input(step = it), Random(42 + it)) }
        assertEquals(a, b)
    }

    @Test
    fun `방 고르기 — 안 나온 방 먼저, 같은 선택지 안에서 겹치지 않게`() {
        val pool = listOf(RoomDef("a", RoomKind.COMBAT), RoomDef("b", RoomKind.COMBAT), RoomDef("c", RoomKind.EVENT))
        repeat(50) { seed ->
            assertEquals("b", Choices.pickRoom(RoomKind.COMBAT, pool, usedRooms = setOf("a"), taken = emptySet(), random = Random(seed))?.id)
            assertEquals("a", Choices.pickRoom(RoomKind.COMBAT, pool, usedRooms = setOf("a", "b"), taken = setOf("b"), random = Random(seed))?.id)
        }
        assertEquals(null, Choices.pickRoom(RoomKind.BOSS, pool, emptySet(), emptySet(), Random(1)))
    }

    @Test
    fun `비밀방 — 못 내면 없음, 찾았으면 더하기, 아니면 확률로 보스 아닌 칸 하나를 ???`() {
        val kinds = listOf(RoomKind.COMBAT, RoomKind.BOSS, RoomKind.REST)
        repeat(100) { seed ->
            assertEquals(Choices.Secret.None, Choices.secret(kinds, available = false, found = true, chancePercent = 100.0, random = Random(seed)))
            assertEquals(Choices.Secret.Extra, Choices.secret(kinds, available = true, found = true, chancePercent = 0.0, random = Random(seed)))
            assertEquals(Choices.Secret.None, Choices.secret(kinds, available = true, found = false, chancePercent = 0.0, random = Random(seed)))
            val hidden = Choices.secret(kinds, available = true, found = false, chancePercent = 100.0, random = Random(seed))
            assertTrue(hidden is Choices.Secret.Hidden && hidden.index in setOf(0, 2), "보스 칸(1)은 안 바꾼다: $hidden")
        }
        // 보스만 남았으면(최대 길이) "???" 는 없다.
        assertEquals(Choices.Secret.None, Choices.secret(listOf(RoomKind.BOSS), available = true, found = false, chancePercent = 100.0, random = Random(1)))
        val rate = (0 until 4000).count { Choices.secret(kinds, true, false, 10.0, Random(it)) is Choices.Secret.Hidden } / 4000.0
        assertTrue(rate in 0.07..0.13, "약 10%: $rate")
    }
}
