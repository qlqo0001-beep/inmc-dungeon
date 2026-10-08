package com.inmc.dungeon.listener

import com.inmc.dungeon.Dungeons
import com.inmc.monster.api.CustomMobDamageEvent
import com.inmc.monster.api.CustomMobDeathEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * 몬스터 플러그인의 사건 — 판의 몬스터는 소환 태그(`dungeon:<판>`)로 알아본다.
 * 난이도의 피해 배율과 축복·저주의 공격·방어는 여기서 곱한다(몬스터 정의를 고치지 않고 — `CustomMobDamageEvent.damage` 는 그러라고 열려 있다).
 */
class MobListener(private val d: Dungeons) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: CustomMobDeathEvent) {
        val run = d.runs.byTag(event.mob.tag) ?: return
        // 잡은 사람에게 판 금화.
        event.killer?.takeIf { run.isMember(it.uniqueId) }?.let { run.addGold(it.uniqueId, run.dungeon.gold.perKill) }
        // 죽은 몹이 추적에서 빠진 다음 틱에 센다.
        Bukkit.getScheduler().runTask(d.plugin, Runnable { if (d.runs.get(run.id) === run) d.runs.checkCleared(run) })
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: CustomMobDamageEvent) {
        val run = d.runs.byTag(event.mob.tag) ?: return
        val player = event.other as? Player
        event.damage *= if (event.outgoing) {
            run.difficulty.mobDamage * (player?.takeIf { run.isMember(it.uniqueId) }?.let { d.effects.defenseFactor(run, it.uniqueId) } ?: 1.0)
        } else {
            run.difficulty.mobTaken * (player?.takeIf { run.isMember(it.uniqueId) }?.let { d.effects.attackFactor(run, it.uniqueId) } ?: 1.0)
        }
    }
}
