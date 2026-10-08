package com.inmc.dungeon.def

import com.inmc.dungeon.Dungeons
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * `rooms/`·`dungeons/` 폴더. 읽기는 켤 때 그 자리에서(첫 판보다 먼저), 리로드는 워커에서 읽고 메인에서 바꿔 끼운다.
 * 읽지 못한 파일은 [problems] 에 남는다(`/던전 관리 검증` 이 보여 준다) — 파일은 건드리지 않는다.
 */
class Library(private val d: Dungeons) {

    @Volatile
    var rooms: Map<String, RoomDef> = emptyMap()
        private set

    @Volatile
    var dungeons: Map<String, DungeonDef> = emptyMap()
        private set

    @Volatile
    var problems: List<String> = emptyList()
        private set

    val roomsDir: File get() = d.io.file("rooms")
    val dungeonsDir: File get() = d.io.file("dungeons")

    fun schematic(id: String): File = File(roomsDir, "$id.schem")

    fun loadNow() {
        apply(read())
    }

    fun reload(then: () -> Unit) {
        d.io.async({ read() }) { apply(it); then() }
    }

    /** 같은 테마의 방(던전의 방 풀). */
    fun pool(dungeon: DungeonDef): List<RoomDef> = rooms.values.filter { it.theme == dungeon.theme }.sortedBy { it.id }

    /** 방 정의를 메모리와 파일에. 건축물(.schem)은 부른 쪽이 먼저 써 둔다. */
    fun putRoom(room: RoomDef) {
        rooms = rooms + (room.id to room)
        val yaml = room.toYaml()
        val file = File(roomsDir, "${room.id}.yml")
        d.io.asyncRun { d.io.save(file, yaml) }
    }

    fun removeRoom(id: String): Boolean {
        if (id !in rooms) return false
        rooms = rooms - id
        d.io.asyncRun {
            File(roomsDir, "$id.yml").delete()
            schematic(id).delete()
        }
        return true
    }

    fun putDungeon(dungeon: DungeonDef) {
        dungeons = dungeons + (dungeon.id to dungeon)
        val yaml = dungeon.toYaml()
        val file = File(dungeonsDir, "${dungeon.id}.yml")
        d.io.asyncRun { d.io.save(file, yaml) }
    }

    private data class Loaded(val rooms: Map<String, RoomDef>, val dungeons: Map<String, DungeonDef>, val problems: List<String>)

    private fun read(): Loaded {
        val problems = ArrayList<String>()
        val rooms = LinkedHashMap<String, RoomDef>()
        roomsDir.mkdirs()
        for (file in roomsDir.listFiles { f -> f.extension == "yml" }.orEmpty().sortedBy { it.name }) {
            val id = file.nameWithoutExtension
            val (room, why) = RoomDef.from(id, YamlConfiguration.loadConfiguration(file))
            when {
                room == null -> problems.add("방 ${file.name}: $why")
                !schematic(id).isFile -> problems.add("방 $id: 건축물 $id.schem 이 없음")
                else -> rooms[id] = room
            }
        }
        val dungeons = LinkedHashMap<String, DungeonDef>()
        dungeonsDir.mkdirs()
        for (file in dungeonsDir.listFiles { f -> f.extension == "yml" }.orEmpty().sortedBy { it.name }) {
            val (dungeon, why) = DungeonDef.from(file.nameWithoutExtension, YamlConfiguration.loadConfiguration(file))
            if (dungeon == null) problems.add("던전 ${file.name}: $why") else dungeons[dungeon.id] = dungeon
        }
        return Loaded(rooms, dungeons, problems)
    }

    private fun apply(loaded: Loaded) {
        rooms = loaded.rooms
        dungeons = loaded.dungeons
        problems = loaded.problems
        for (problem in loaded.problems) d.logger.warning(problem)
    }
}
