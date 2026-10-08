package com.inmc.dungeon.listener

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent
import com.inmc.dungeon.Dungeons
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent
import org.bukkit.entity.EnderPearl
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerTeleportEvent

/**
 * 던전 월드에서 길을 건너뛰거나 빠져나가는 것 막기(추천 15) — 모험 모드만으로는 엔더진주·후렴과·겉날개·순간이동 명령이 그대로 된다.
 * 관리자는 막지 않는다. 다른 길로 던전 월드를 떠나면 "나감"으로 처리한다.
 */
class GuardListener(private val d: Dungeons) : Listener {

    private fun guarded(player: Player): Boolean =
        d.world.isDungeon(player.world) && !player.hasPermission(Dungeons.ADMIN)

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!guarded(event.player)) return
        val label = event.message.removePrefix("/").substringBefore(' ').substringAfter(':').lowercase()
        if (label !in d.config.blockedCommands) return
        event.isCancelled = true
        d.messages.send(event.player, "use-leave")
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPearl(event: PlayerLaunchProjectileEvent) {
        if (event.projectile is EnderPearl && guarded(event.player)) {
            event.isCancelled = true
            d.messages.send(event.player, "blocked")
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val player = event.player
        if (event.cause == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT && guarded(player)) {
            event.isCancelled = true
            return
        }
        // 던전 월드를 다른 길로 떠난다 — 판에서 뺀다(이미 가는 중이니 순간이동은 하지 않는다).
        if (!d.world.isDungeon(event.from.world) || d.world.isDungeon(event.to.world)) return
        if (d.runs.isExiting(player.uniqueId)) return
        if (d.runs.of(player.uniqueId) != null) d.runs.leave(player, teleport = false)
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onGlide(event: EntityToggleGlideEvent) {
        val player = event.entity as? Player ?: return
        if (event.isGliding && guarded(player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onEnderChest(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        if (event.inventory.type == InventoryType.ENDER_CHEST && guarded(player)) {
            event.isCancelled = true
            d.messages.send(player, "blocked")
        }
    }

    /**
     * 들어오기 **전에** 자리를 정한다 — 돌아갈 자리가 남은 사람(판이 끝났거나 크러시로 월드가 새로 만들어진 뒤)은 원래 자리로.
     * 그대로 두면 사라진 던전 월드의 좌표 그대로 본 월드에 떨어진다(땅속·허공). inmc-menu(HIGH)보다 늦게 — 우리가 이긴다.
     * 비동기 사건이다 — 메모리 맵만 읽는다.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onSpawnLocation(event: AsyncPlayerSpawnLocationEvent) {
        val id = event.connection.profile.id ?: return
        if (d.runs.ofAsync(id)) return
        val point = d.returns.get(id) ?: return
        point.location()?.let { event.spawnLocation = it }
    }
}
