package com.inmc.dungeon.listener

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent
import com.inmc.dungeon.Dungeons
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent
import org.bukkit.Bukkit
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

    // LOW — inmc-essentials 의 한/영 자판 변환이 LOWEST 에서 '/tmvhs' 를 '/스폰' 으로 고쳐 쓴다. 같은 LOWEST 면 순서가 등록 순서라
    // 고치기 전의 이름을 보고 막지 못할 수 있다. 한 단계 늦게 보면 늘 고친 뒤의 이름을 본다(취소는 여전히 쿨타임·대기·비용보다 먼저).
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!guarded(event.player)) return
        if (!blocked(event.message.removePrefix("/").substringBefore(' '))) return
        event.isCancelled = true
        d.messages.send(event.player, "use-leave")
    }

    /**
     * 친 이름 · 그 명령의 본이름 · 별칭 중 하나라도 막는 목록에 있으면 막는다. inmc-essentials 는 한글이 본이름이고 영어가 별칭이라(`워프` ↔ `warp`)
     * 목록에 `warp` 만 있어도 `/워프` 가 막힌다(목록을 이름마다 다 적지 않아도 되게). 명령 표에 없는 이름은 친 글자만 견준다.
     */
    private fun blocked(typed: String): Boolean {
        val list = d.config.blockedCommands
        if (typed.substringAfter(':').lowercase() in list) return true
        val command = runCatching { Bukkit.getCommandMap().getCommand(typed.lowercase()) }.getOrNull() ?: return false
        return (listOf(command.name, command.label) + command.aliases).any { it.substringAfter(':').lowercase() in list }
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
        // 판에 없는 사람이 다른 길(이동 요청·워프·/tp)로 던전 월드에 들어온다 — 막는다(관리자 빼고). 던전 월드는 여러 모드가 빌려 쓰는 월드라
        // 남이 들어오면 판 안의 물건이 모드를 넘는다(core `Realms` — inmc-worlds, 2026-10-15). 판에 든 사람은 시작할 때 이미 판에 적혀 있다.
        if (d.world.isDungeon(event.to.world) && !d.world.isDungeon(event.from.world) &&
            d.runs.of(player.uniqueId) == null && !player.hasPermission(Dungeons.ADMIN)
        ) {
            event.isCancelled = true
            d.messages.send(player, "blocked")
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
     * 그대로 두면 사라진 던전 월드의 좌표 그대로 본 월드에 떨어진다(땅속·허공). 스폰 담당 inmc-essentials(HIGH — 2026-10-10 inmc-menu 에서 옮겨 감)보다 늦게 — 우리가 이긴다.
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
