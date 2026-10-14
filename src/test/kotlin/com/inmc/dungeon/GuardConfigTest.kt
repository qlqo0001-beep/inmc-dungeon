package com.inmc.dungeon

import com.inmc.dungeon.config.DungeonConfig
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 던전 안 명령 막기 목록 — 예전 config.yml 이 기본 목록보다 약해지지 않는다. */
class GuardConfigTest {

    @Test
    fun `예전 목록을 그대로 둬도 기본 목록이 전부 막힌다`() {
        val old = YamlConfiguration().apply {
            set("blocked-commands", listOf("spawn", "home", "homes", "sethome", "tpa", "tpahere", "tpaccept", "warp", "warps", "back", "tp", "tpo", "rtp", "wild", "야생", "스폰", "홈"))
        }
        val blocked = DungeonConfig.from(old).blockedCommands
        for (name in listOf("이동", "tpto", "좌표이동", "tppos", "사망지점", "dback", "tpaall", "tpyes")) assertTrue(name in blocked, name)
        assertTrue(DungeonConfig.DEFAULT_BLOCKED.all { it in blocked })
    }

    @Test
    fun `적은 것은 기본 목록 위에 더해진다 — 슬래시 떼고 소문자`() {
        val blocked = DungeonConfig.blocked(listOf("/Fly", "", "Hub"))
        assertTrue("fly" in blocked)
        assertTrue("hub" in blocked)
        assertEquals(DungeonConfig.DEFAULT_BLOCKED.size + 2, blocked.size)
    }

    @Test
    fun `목록이 없으면 기본 목록 그대로`() {
        assertEquals(DungeonConfig.DEFAULT_BLOCKED, DungeonConfig.from(YamlConfiguration()).blockedCommands)
    }
}
