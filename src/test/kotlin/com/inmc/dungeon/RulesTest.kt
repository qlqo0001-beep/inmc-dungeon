package com.inmc.dungeon

import com.inmc.dungeon.def.Markers
import com.inmc.dungeon.def.RoomKind
import com.inmc.dungeon.def.Vec
import com.inmc.dungeon.run.Scaling
import com.inmc.dungeon.world.Cells
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 인원 보정 · 칸 · 표지판 읽기. */
class RulesTest {

    @Test
    fun `인원 보정 — 혼자면 그대로, 한 명 늘 때마다 더한다`() {
        assertEquals(6, Scaling.count(6, 1.0, 1, 0.3))
        assertEquals(10, Scaling.count(6, 1.0, 3, 0.3)) // 6 × 1.6 = 9.6
        assertEquals(0, Scaling.count(0, 2.0, 4, 0.3))
        assertEquals(1, Scaling.count(1, 0.1, 1, 0.0), "적어도 한 마리")
        assertEquals(1.5 * 1.5, Scaling.health(1.5, 3, 0.25), 1e-9)
    }

    @Test
    fun `칸 — 가장 작은 빈 칸, 다 차면 없음, 돌려주면 다시`() {
        val cells = Cells(2)
        assertEquals(0, cells.acquire())
        assertEquals(1, cells.acquire())
        assertNull(cells.acquire())
        cells.release(0)
        assertEquals(0, cells.acquire())
        assertEquals(2, cells.activeCount)
        cells.max = 3
        assertEquals(2, cells.acquire())
    }

    @Test
    fun `칸 좌표 — 칸은 z, 자리는 x, 0 근처는 비운다`() {
        assertEquals(Triple(0, 100, 1024), Cells.origin(0, 0, 1024, 256, 100))
        assertEquals(Triple(256, 100, 3072), Cells.origin(2, 1, 1024, 256, 100))
    }

    @Test
    fun `표지판 첫 줄 — 대괄호·대소문자·영문 별칭`() {
        assertEquals(Markers.Tag.ARRIVAL, Markers.tagOf("[도착]"))
        assertEquals(Markers.Tag.EXIT, Markers.tagOf(" [ 출구 ] "))
        assertEquals(Markers.Tag.MOB, Markers.tagOf("[Mob]"))
        assertEquals(Markers.Tag.URB, Markers.tagOf("[URB]"))
        assertNull(Markers.tagOf("환영합니다"))
    }

    @Test
    fun `출구는 둘째 줄 숫자 순, 셋까지, 중복 도착은 알린다`() {
        val builder = Markers.Builder()
        builder.add(Markers.Tag.EXIT, Vec(1, 0, 0), "3", 0f)
        builder.add(Markers.Tag.EXIT, Vec(2, 0, 0), "1", 0f)
        builder.add(Markers.Tag.EXIT, Vec(3, 0, 0), "", 0f)
        builder.add(Markers.Tag.EXIT, Vec(4, 0, 0), "2", 0f)
        builder.add(Markers.Tag.ARRIVAL, Vec(0, 1, 0), "", 90f)
        builder.add(Markers.Tag.ARRIVAL, Vec(9, 1, 9), "", 0f)
        builder.add(Markers.Tag.MOB, Vec(5, 1, 5), "zombie_knight", 0f)
        val markers = builder.build()
        assertEquals(listOf(Vec(2, 0, 0), Vec(4, 0, 0), Vec(1, 0, 0)), markers.exits)
        assertEquals(Vec(0, 1, 0), markers.arrival?.at)
        assertEquals(90f, markers.arrival?.yaw)
        assertEquals("zombie_knight", markers.mobs.single().mob)
        assertTrue(builder.problems.size == 2, "중복 도착 + 출구 넷: ${builder.problems}")
    }

    @Test
    fun `비밀 표지판 — 하나만, 저장했다 읽어도 같다`() {
        assertEquals(Markers.Tag.SECRET, Markers.tagOf("[비밀]"))
        assertEquals(Markers.Tag.SECRET, Markers.tagOf("[Secret]"))
        val builder = Markers.Builder()
        builder.add(Markers.Tag.SECRET, Vec(3, 2, 1), "", 0f)
        builder.add(Markers.Tag.SECRET, Vec(9, 9, 9), "", 0f)
        builder.add(Markers.Tag.EXIT, Vec(1, 0, 0), "", 0f)
        val markers = builder.build()
        assertEquals(Vec(3, 2, 1), markers.secret)
        assertEquals(1, builder.problems.size, builder.problems.toString())
        val yaml = org.bukkit.configuration.file.YamlConfiguration()
        markers.save(yaml.createSection("markers"))
        assertEquals(markers, Markers.load(org.bukkit.configuration.file.YamlConfiguration().apply { loadFromString(yaml.saveToString()) }.getConfigurationSection("markers")))
    }

    @Test
    fun `방 종류 명령 단어는 겹치지 않는다`() {
        val words = RoomKind.entries.map { it.word }
        assertEquals(words.size, words.toSet().size, words.toString())
        assertEquals("정예", RoomKind.ELITE.word)
        assertEquals("시작", RoomKind.START.word)
        assertEquals(RoomKind.COMBAT, RoomKind.of("combat"))
        assertEquals(RoomKind.BOSS, RoomKind.of("BOSS"))
    }
}
