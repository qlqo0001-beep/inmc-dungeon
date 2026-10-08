package com.inmc.dungeon.run

import com.inmc.dungeon.Dungeons
import com.inmc.dungeon.def.Buff
import com.inmc.dungeon.def.BuffStat
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 축복·저주(`buffs.yml`) 붙이기·떼기. 속성은 **저장 안 되는 수정자**(`addTransientModifier`)로 — 크러시 뒤 접속해도 남지 않는다.
 * 공격·방어는 속성이 아니라 던전 몬스터와 주고받는 피해에 곱한다(`listener/MobListener` — 커스텀아이템 전투 계산 뒤라 겹치지 않는다).
 */
class Effects(private val d: Dungeons) {

    @Volatile
    var buffs: Map<String, Buff> = emptyMap()
        private set

    private val attributes = mapOf(
        BuffStat.MAX_HEALTH to (Attribute.MAX_HEALTH to AttributeModifier.Operation.ADD_NUMBER),
        BuffStat.SPEED to (Attribute.MOVEMENT_SPEED to AttributeModifier.Operation.ADD_SCALAR),
        BuffStat.ATTACK_SPEED to (Attribute.ATTACK_SPEED to AttributeModifier.Operation.ADD_SCALAR),
    )

    private fun key(stat: BuffStat) = NamespacedKey(Dungeons.NAMESPACE, "buff_" + stat.key.replace('-', '_'))

    fun loadNow() {
        buffs = Buff.loadAll(YamlConfiguration.loadConfiguration(d.io.file("buffs.yml")))
    }

    /** [player] 에게 [buffId] 를 붙인다. 모르는 id 면 false. */
    fun give(run: Run, player: Player, buffId: String): Boolean {
        val buff = buffs[buffId] ?: return false
        run.buffs.getOrPut(player.uniqueId) { ArrayList() }.add(buff.id)
        apply(run, player)
        return true
    }

    /** 붙은 것 가운데 [stat] 의 합. */
    fun total(run: Run, player: UUID, stat: BuffStat): Double =
        run.buffs[player].orEmpty().sumOf { buffs[it]?.stats?.get(stat) ?: 0.0 }

    /** 던전 몬스터에게 주는 피해 배율. */
    fun attackFactor(run: Run, player: UUID): Double = (1.0 + total(run, player, BuffStat.ATTACK)).coerceAtLeast(0.1)

    /** 던전 몬스터에게 받는 피해 배율. */
    fun defenseFactor(run: Run, player: UUID): Double = (1.0 - total(run, player, BuffStat.DEFENSE)).coerceIn(0.1, 3.0)

    /** 속성 수정자를 지금 붙은 것에 맞춘다. */
    fun apply(run: Run, player: Player) {
        for ((stat, pair) in attributes) {
            val (attribute, operation) = pair
            val instance = player.getAttribute(attribute) ?: continue
            instance.removeModifier(key(stat))
            val value = total(run, player.uniqueId, stat)
            if (value != 0.0) instance.addTransientModifier(AttributeModifier(key(stat), value, operation))
        }
        player.getAttribute(Attribute.MAX_HEALTH)?.value?.let { max -> if (player.health > max) player.health = max }
    }

    /** 판에서 나갈 때 — 붙인 수정자를 전부 뗀다. */
    fun clear(player: Player) {
        for ((stat, pair) in attributes) player.getAttribute(pair.first)?.removeModifier(key(stat))
        player.getAttribute(Attribute.MAX_HEALTH)?.value?.let { max -> if (player.health > max) player.health = max }
    }
}
