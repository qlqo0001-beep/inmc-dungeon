package com.inmc.dungeon.listener

import com.inmc.dungeon.Dungeons
import org.bukkit.entity.Enemy
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent

/** 필드 포탈 — 몹 처치로 생기기 · 입구에 들어서기. 파티 — 나가면 로비에서 빠지기. */
class PortalListener(private val d: Dungeons) : Listener {

    /** 플레이어가 잡은 · 자연 스폰된 적대 몹만(추천 4 — 스포너·농장으로 포탈을 쏟아내지 않게). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (entity !is Enemy) return
        val killer = entity.killer ?: return
        if (entity.entitySpawnReason != CreatureSpawnEvent.SpawnReason.NATURAL) return
        d.portals.onKill(killer, entity.location)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!event.hasChangedBlock() || d.world.isDungeon(event.to.world)) return
        d.portals.onMove(event.player, event.to)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        d.lobbies.onQuit(event.player)
    }
}
