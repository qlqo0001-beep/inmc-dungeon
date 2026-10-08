package com.inmc.dungeon

import com.inmc.dungeon.config.DungeonConfig
import com.inmc.dungeon.config.Messages
import com.inmc.dungeon.def.DifficultyMode
import com.inmc.dungeon.def.DungeonDef
import com.inmc.dungeon.def.Markers
import com.inmc.dungeon.def.RoomDef
import com.inmc.dungeon.def.RoomKind
import com.inmc.dungeon.def.Spot
import com.inmc.dungeon.def.Vec
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 배포 파일 · 정의 왕복. */
class ResourceTest {

    private fun yaml(path: String): YamlConfiguration =
        javaClass.classLoader.getResourceAsStream(path)!!.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }

    private val code: String by lazy {
        File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
    }

    @Test
    fun `배포 메시지와 기본값 표의 키가 정확히 같다`() {
        assertEquals(Messages.DEFAULTS.keys, yaml("messages.yml").getKeys(false))
    }

    @Test
    fun `코드가 부르는 메시지 키가 전부 있다`() {
        // 없는 키는 오류 없이 빈 줄이 된다 — 무엇이 실패했는지 아무도 모른다.
        val sends = Regex("""send\([^,()]+, (?:if \([^)]*\) )?"([a-z-]+)"(?: else "([a-z-]+)")?""").findAll(code)
            .flatMap { listOf(it.groupValues[1], it.groupValues[2]) }.filter { it.isNotEmpty() }.toSet()
        val tells = Regex("""tell\((?:run|lobby), (?:if \([^)]*\) )?"([a-z-]+)"(?: else "([a-z-]+)")?""").findAll(code)
            .flatMap { listOf(it.groupValues[1], it.groupValues[2]) }.filter { it.isNotEmpty() }.toSet()
        val returned = Regex("""return "([a-z-]+)"""").findAll(code).map { it.groupValues[1] }.toSet()
        val failReasons = Regex("""reason = "([a-z-]+)"""").findAll(code).map { "fail-" + it.groupValues[1] }
            .filter { it != "fail-cleared" && it != "fail-no-more-rooms" }.toSet()
        val errors = Regex("""\?: "([a-z][a-z-]+)"""").findAll(code).map { it.groupValues[1] }.toSet()
        val used = sends + tells + returned + failReasons + errors
        assertTrue(used.size > 30, "키를 못 읽었다: $used")
        assertEquals(emptySet(), used - Messages.DEFAULTS.keys)
    }

    @Test
    fun `배포 설정은 코드의 기본값과 같다`() {
        assertEquals(DungeonConfig(), DungeonConfig.from(yaml("config.yml")))
    }

    @Test
    fun `코드가 쓰는 권한은 전부 선언돼 있다`() {
        val yml = File("src/main/resources/paper-plugin.yml").readText()
        for (node in listOf(Dungeons.ADMIN, Dungeons.PLAY)) assertTrue(Regex("""(?m)^  ${Regex.escape(node)}:""").containsMatchIn(yml), node)
    }

    @Test
    fun `예시 던전을 읽는다`() {
        val (dungeon, why) = DungeonDef.from("example", yaml("dungeons/example.yml"))
        assertNotNull(dungeon, why)
        assertEquals("sample", dungeon.theme)
        assertEquals(3..5, dungeon.length)
        assertEquals(listOf("normal", "hard", "hell"), dungeon.difficulties.map { it.id })
        assertEquals(DifficultyMode.CHOOSE, dungeon.difficultyMode)
        assertEquals(1, dungeon.maxPerKind[RoomKind.TREASURE])
        assertEquals(50, dungeon.weights[RoomKind.COMBAT])
        assertEquals(6, dungeon.shop.size)
        assertEquals("dungeon:revive", dungeon.shop.first().item)
        assertEquals("might", dungeon.shop.last().buff)
        assertEquals(listOf("might", "guard", "swift", "vitality", "fortune"), dungeon.blessings)
        assertEquals(2, dungeon.gold.perKill)
    }

    @Test
    fun `배포 축복·저주 — 예시 던전이 쓰는 id 가 전부 있고 수치를 읽는다`() {
        val buffs = com.inmc.dungeon.def.Buff.loadAll(yaml("buffs.yml"))
        val (dungeon, _) = DungeonDef.from("example", yaml("dungeons/example.yml"))
        for (id in dungeon!!.blessings + dungeon.curses + dungeon.shop.map { it.buff }.filter(String::isNotEmpty)) assertTrue(id in buffs, id)
        assertEquals(0.2, buffs["might"]!!.stats[com.inmc.dungeon.def.BuffStat.ATTACK])
        assertEquals(-6.0, buffs["withered"]!!.stats[com.inmc.dungeon.def.BuffStat.MAX_HEALTH])
        assertTrue(buffs.values.flatMap { it.stats.keys }.toSet().size >= 5, "수치 종류를 고루 쓴다")
    }

    @Test
    fun `던전 정의 왕복`() {
        val (dungeon, _) = DungeonDef.from("example", yaml("dungeons/example.yml"))
        val again = DungeonDef.from("example", YamlConfiguration().apply { loadFromString(dungeon!!.toYaml().saveToString()) }).first
        assertEquals(dungeon, again)
    }

    @Test
    fun `방 정의 왕복 — 표시·수량·몬스터`() {
        val room = RoomDef(
            id = "forest_combat_01", kind = RoomKind.COMBAT, theme = "forest", name = "<green>숲길", risk = 3, reward = "일반",
            mobs = listOf(RoomDef.MobEntry("zombie_knight", 3), RoomDef.MobEntry("archer", 1)), count = 5..8, size = Vec(30, 12, 28),
            markers = Markers(
                arrival = Spot(Vec(2, 1, 14), 90f), exits = listOf(Vec(28, 1, 4), Vec(28, 1, 14)),
                mobs = listOf(Markers.MobSpot(Vec(10, 1, 10)), Markers.MobSpot(Vec(12, 1, 12), "archer")), chest = Vec(15, 1, 15),
            ),
            description = listOf("어두운 숲"),
        )
        val back = RoomDef.from(room.id, YamlConfiguration().apply { loadFromString(room.toYaml().saveToString()) }).first
        assertEquals(room, back)
    }

    @Test
    fun `잘못된 id 는 거절`() {
        assertEquals(null, RoomDef.from("숲방", YamlConfiguration().apply { set("kind", "combat") }).first)
        assertEquals(null, RoomDef.from("a.b", YamlConfiguration().apply { set("kind", "combat") }).first)
        assertEquals(null, RoomDef.from("ok", YamlConfiguration().apply { set("kind", "nope") }).first)
        assertEquals(5..8, RoomDef.parseRange("5~8"))
        assertEquals(3..3, RoomDef.parseRange("3"))
    }
}
