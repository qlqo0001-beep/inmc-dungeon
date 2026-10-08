package com.inmc.dungeon.listener

import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent
import com.inmc.dungeon.Dungeons
import com.inmc.dungeon.run.Run
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent

/** 판 안의 플레이어 사건 — 쓰러짐 · 선택지 클릭 · 문 · 관전 · 접속. */
class RunListener(private val d: Dungeons) : Listener {

    /**
     * 치명 피해를 막고 쓰러뜨린다(추천 6). 모든 계산이 끝난 뒤(HIGHEST)의 최종 피해로 판단한다.
     * 불사의 토템을 들고 있으면 바닐라에 맡긴다 — 토템이 살린다.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val run = d.runs.of(player.uniqueId) ?: return
        if (!d.world.isDungeon(player.world)) return
        if (run.phase == Run.Phase.ENDING || player.uniqueId in run.downed ||
            (run.graceUntil[player.uniqueId] ?: 0L) > System.currentTimeMillis()
        ) {
            event.isCancelled = true
            return
        }
        if (event.finalDamage < player.health + player.absorptionAmount) return
        if (holdsTotem(player)) return
        event.isCancelled = true
        d.runs.down(run, player)
    }

    /** 만에 하나 진짜로 죽었을 때(피해 사건을 거치지 않는 죽음) — 아무것도 잃지 않고, 쓰러진 것으로 친다. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: PlayerDeathEvent) {
        val run = d.runs.of(event.player.uniqueId) ?: return
        if (!d.world.isDungeon(event.player.world)) return
        event.keepInventory = true
        event.drops.clear()
        event.keepLevel = true
        event.droppedExp = 0
        event.deathMessage(null)
        d.runs.down(run, event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onRespawn(event: PlayerRespawnEvent) {
        val run = d.runs.of(event.player.uniqueId) ?: return
        run.current?.let { event.respawnLocation = it.arrival }
    }

    // --- 선택지 클릭(우·좌 둘 다) -------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEntityEvent) {
        if (vote(event.player, event.rightClicked)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onAttack(event: PrePlayerAttackEntityEvent) {
        if (vote(event.player, event.attacked)) event.isCancelled = true
    }

    private fun vote(player: Player, entity: Entity): Boolean {
        if (entity !is Interaction) return false
        // 방의 자리(보상 상자·상점·축복·제단).
        d.features.stationOf(entity)?.let { (runId, type) ->
            d.features.use(player, runId, type, entity)
            return true
        }
        // 필드 포탈을 누르면 입장 화면(입구에 서 있어 다시 들어서지 않아도 다시 열 수 있게).
        d.portals.portalOf(entity)?.let { id ->
            d.portals.get(id)?.let { d.portals.enter(player, it) }
            return true
        }
        val runId = d.holograms.runOf(entity) ?: return false
        val index = d.holograms.choiceOf(entity) ?: return false
        d.runs.vote(player, runId, index)
        return true
    }

    // --- 던전 내장 아이템(즉시부활·부활권·파티 회복·정화) -----------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    fun onUse(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val stack = event.item ?: return
        val builtin = d.tempItems.builtinOf(stack) ?: return
        event.isCancelled = true
        val player = event.player
        val run = d.runs.of(player.uniqueId)
        if (run == null || !run.alive(player.uniqueId)) return d.messages.send(player, "item-outside")
        if (d.features.useItem(run, player, builtin)) stack.amount -= 1
    }

    // --- 문 ---------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!event.hasChangedPosition() || !d.world.isDungeon(event.to.world)) return
        d.runs.tryDoor(event.player, event.to)
    }

    // --- 관전 -------------------------------------------------------------------------

    /** 쓰러진 사람이 웅크려 카메라에서 빠져나와 벽을 뚫고 돌아다니지 못하게. */
    @EventHandler(ignoreCancelled = true)
    fun onStopSpectating(event: PlayerStopSpectatingEntityEvent) {
        val run = d.runs.of(event.player.uniqueId) ?: return
        if (event.player.uniqueId in run.downed && d.runs.hasAliveTarget(run)) event.isCancelled = true
    }

    // --- 접속 -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        d.preview.end(event.player, teleport = false)
        d.runs.onQuit(event.player)
    }

    /** 판이 살아 있으면 그 판으로, 아니면 돌아갈 자리가 남아 있을 때(크러시 뒤) 원래 자리·게임 모드로. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (d.runs.onJoin(player)) return
        if (d.returns.has(player.uniqueId)) d.runs.restore(player)
    }

    private fun holdsTotem(player: Player): Boolean =
        player.inventory.itemInMainHand.type == Material.TOTEM_OF_UNDYING || player.inventory.itemInOffHand.type == Material.TOTEM_OF_UNDYING
}
