package com.inmc.dungeon.player

import com.inmc.dungeon.Dungeons
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 던전에 들어가기 전 자리·게임 모드 — `returns/<uuid>.yml`. **들어갈 때 바로 원자적으로** 쓴다(PlayerStore 의 30초 묶음 저장이 아니라).
 * 그 30초 사이에 서버가 죽으면 다시 켤 때 던전 월드는 새로 만들어지고, 그 사람은 사라진 월드의 좌표 그대로 본 월드에 떨어진다 — 땅속이나 허공.
 *
 * 있으면 = 아직 돌려보내지 않은 사람. 판이 끝나 돌려보내면 지운다. 접속할 때 이 파일이 있고 판에 없으면 여기로 돌려보낸다
 * (`listener/SpawnListener` — 들어오기 전에 자리를 정한다).
 *
 * 메모리 맵은 비동기 접속 사건에서도 읽는다(ConcurrentHashMap). 파일 쓰기·지우기는 core `ConfigService` 의 단일 워커 — 순서가 지켜진다.
 */
class Returns(private val d: Dungeons) {

    data class Point(
        val world: String,
        val x: Double, val y: Double, val z: Double,
        val yaw: Float, val pitch: Float,
        val gameMode: GameMode,
    ) {
        fun location(): Location? = Bukkit.getWorld(world)?.let { Location(it, x, y, z, yaw, pitch) }

        companion object {
            fun of(location: Location, gameMode: GameMode) =
                Point(location.world.name, location.x, location.y, location.z, location.yaw, location.pitch, gameMode)
        }
    }

    private val points = ConcurrentHashMap<UUID, Point>()

    private val dir: File get() = d.io.file("returns")

    fun get(player: UUID): Point? = points[player]

    fun has(player: UUID): Boolean = points.containsKey(player)

    /** 켤 때 그 자리에서 전부 읽는다(몇 개 안 된다 — 판 안에 있던 사람뿐). */
    fun loadNow() {
        dir.mkdirs()
        for (file in dir.listFiles { f -> f.extension == "yml" }.orEmpty()) {
            val id = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull() ?: continue
            val yaml = YamlConfiguration.loadConfiguration(file)
            val world = yaml.getString("world") ?: continue
            points[id] = Point(
                world, yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                yaml.getDouble("yaw").toFloat(), yaml.getDouble("pitch").toFloat(),
                runCatching { GameMode.valueOf(yaml.getString("gamemode", "SURVIVAL")!!) }.getOrDefault(GameMode.SURVIVAL),
            )
        }
    }

    /** 이미 있으면 덮지 않는다 — 판에서 판으로 바로 들어가도 처음 자리로 돌아가게. */
    fun remember(player: UUID, point: Point) {
        if (points.putIfAbsent(player, point) != null) return
        val yaml = YamlConfiguration()
        yaml.set("world", point.world)
        yaml.set("x", point.x)
        yaml.set("y", point.y)
        yaml.set("z", point.z)
        yaml.set("yaw", point.yaw.toDouble())
        yaml.set("pitch", point.pitch.toDouble())
        yaml.set("gamemode", point.gameMode.name)
        val text = yaml.saveToString()
        val file = File(dir, "$player.yml")
        d.io.asyncRun { writeAtomically(file, text) }
    }

    fun forget(player: UUID) {
        if (points.remove(player) == null) return
        val file = File(dir, "$player.yml")
        d.io.asyncRun { file.delete() }
    }

    /** 끌 때 — 워커를 기다리지 않고 그 자리에서. */
    fun forgetNow(player: UUID) {
        if (points.remove(player) == null) return
        File(dir, "$player.yml").delete()
    }

    private fun writeAtomically(file: File, text: String) = kr.inmc.core.util.AtomicFiles.write(file, text)
}
