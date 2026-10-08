package com.inmc.dungeon

import com.inmc.dungeon.def.Vec
import com.inmc.dungeon.portal.PortalShape
import com.inmc.dungeon.portal.Portals
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PortalTest {

    @Test
    fun `회전 — 위에서 본 시계 방향 90도씩, 네 번이면 제자리`() {
        assertEquals(0 to 0, Portals.rotate(0, 0, 1))
        assertEquals(-3 to 2, Portals.rotate(2, 3, 1))
        assertEquals(-2 to -3, Portals.rotate(2, 3, 2))
        assertEquals(3 to -2, Portals.rotate(2, 3, 3))
        assertEquals(2 to 3, Portals.rotate(2, 3, 4))
        assertEquals(Portals.rotate(2, 3, 3), Portals.rotate(2, 3, -1))
    }

    @Test
    fun `기본 모양 — 틀과 안쪽, 입구는 틀 안 바닥`() {
        val shape = PortalShape.DEFAULT
        assertEquals(30, shape.blocks.size)
        assertTrue(shape.blocks.any { it.second.contains("crying_obsidian") })
        assertTrue(shape.blocks.any { it.first == shape.entry && it.second.contains("glass") })
    }

    @Test
    fun `모양 왕복`() {
        val shape = PortalShape("arch", listOf(Vec(0, 0, 0) to "minecraft:stone", Vec(1, 2, 0) to "minecraft:oak_stairs[facing=east,half=bottom]"), Vec(1, 0, 0), Vec(2, 3, 1))
        val back = PortalShape.from("arch", YamlConfiguration().apply { loadFromString(shape.toYaml().saveToString()) })
        assertNotNull(back)
        assertEquals(shape, back)
        assertEquals(null, PortalShape.from("empty", YamlConfiguration()))
    }
}
