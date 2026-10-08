package com.inmc.dungeon.run

import com.inmc.dungeon.Dungeons
import com.inmc.dungeon.def.RoomDef
import com.inmc.dungeon.def.Vec
import com.inmc.dungeon.player.Returns
import com.inmc.dungeon.world.Cells
import kr.inmc.core.util.Text
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import java.util.UUID

/**
 * 관리자 `/던전 관리 방 시험 <id>` — 빈 칸에 방 하나만 붙이고 그 안으로 보내, 표지판으로 찍은 자리를 떠 있는 글자로 보여 준다.
 * 판을 열지 않으니 몬스터·투표는 없다. 칸 하나를 쓰므로 열린 판 수에 들어간다.
 */
class Preview(private val d: Dungeons) {

    private class Session(val cell: Int, val origin: Location, val room: RoomDef, val displays: MutableList<Entity> = ArrayList())

    private val sessions = HashMap<UUID, Session>()

    fun active(player: UUID): Boolean = player in sessions

    /** 안 되면 메시지 키. */
    fun start(player: Player, room: RoomDef): String? {
        val world = d.world.world ?: return "world-not-ready"
        if (d.runs.of(player.uniqueId) != null) return "already-in-run"
        end(player, teleport = false)
        val cell = d.runs.cells.acquire() ?: return "full"
        val (x, y, z) = Cells.origin(cell, 0, d.config.cellSpacing, d.config.slotSpacing, d.config.baseY)
        val origin = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        val session = Session(cell, origin, room)
        sessions[player.uniqueId] = session
        d.returns.remember(player.uniqueId, Returns.Point.of(player.location, player.gameMode))
        d.schematics.paste(d.library.schematic(room.id), world, Vec(x, y, z)) { error ->
            if (sessions[player.uniqueId] !== session) return@paste
            if (error != null) {
                d.messages.send(player, "room-save-failed", com.inmc.dungeon.util.Ph.of().value(error))
                end(player)
                return@paste
            }
            val placed = Run.Placed(room, 0, origin)
            showMarkers(session, placed)
            player.gameMode = GameMode.CREATIVE
            player.teleportAsync(placed.arrival)
            d.messages.send(player, "preview-started", com.inmc.dungeon.util.Ph.of().id(room.id))
        }
        return null
    }

    fun end(player: Player, teleport: Boolean = true): Boolean {
        val session = sessions.remove(player.uniqueId) ?: return false
        session.displays.forEach(Entity::remove)
        val placed = Run.Placed(session.room, 0, session.origin)
        d.runs.clearArea(session.origin.world, placed.min, placed.max) { d.runs.cells.release(session.cell) }
        if (teleport) d.runs.restore(player)
        return true
    }

    /** 끌 때 — 그 자리에서 돌려보낸다(비동기 순간이동을 기다릴 수 없다). 월드는 다음에 켤 때 새로 만들어지니 블록은 두고. */
    fun endAll() {
        for (id in sessions.keys.toList()) {
            val session = sessions.remove(id) ?: continue
            session.displays.forEach(Entity::remove)
            d.runs.cells.release(session.cell)
            val player = org.bukkit.Bukkit.getPlayer(id) ?: continue
            val point = d.returns.get(id)
            player.gameMode = point?.gameMode ?: GameMode.SURVIVAL
            player.teleport(point?.location() ?: org.bukkit.Bukkit.getWorlds().first().spawnLocation)
            d.returns.forgetNow(id)
        }
    }

    private fun showMarkers(session: Session, placed: Run.Placed) {
        val m = session.room.markers
        fun label(at: Location, text: String) {
            session.displays.add(at.world.spawn(at.clone().add(0.0, 1.4, 0.0), TextDisplay::class.java) {
                it.isPersistent = false
                it.text(Text.renderFlat(text))
                it.billboard = Display.Billboard.CENTER
                it.isSeeThrough = true
            })
        }
        m.arrival?.let { label(placed.at(it.at), "<green>[도착]</green>") }
        m.exits.forEachIndexed { i, v -> label(placed.at(v), "<aqua>[출구 ${i + 1}]</aqua>") }
        m.mobs.forEach { label(placed.at(it.at), "<red>[몬스터]</red>" + (it.mob?.let { id -> " <gray>$id</gray>" } ?: "")) }
        m.boss?.let { label(placed.at(it), "<dark_red>[보스]</dark_red>") }
        m.chest?.let { label(placed.at(it), "<gold>[보상]</gold>") }
        m.urb.forEach { label(placed.at(it), "<yellow>[URB]</yellow>") }
        m.event.forEach { label(placed.at(it), "<light_purple>[이벤트]</light_purple>") }
    }
}
