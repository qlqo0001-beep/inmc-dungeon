package com.inmc.dungeon.def

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration

/**
 * 판 동안만 붙는 축복·저주(사양 16절) — `buffs.yml`. 판이 끝나거나 나가면(크러시 뒤 접속 포함) 전부 뗀다.
 * 수치는 비율(0.15 = 15%), 최대 체력만 반 칸 단위(2 = 하트 하나).
 */
data class Buff(
    val id: String,
    val name: String,
    val icon: Material = Material.POTION,
    val description: List<String> = emptyList(),
    val stats: Map<BuffStat, Double> = emptyMap(),
) {

    /** 화면에 보일 수치 줄들. */
    fun lines(): List<String> = stats.map { (stat, value) -> stat.describe(value) }

    companion object {

        fun loadAll(yaml: YamlConfiguration): Map<String, Buff> = yaml.getKeys(false).mapNotNull { id ->
            val s = yaml.getConfigurationSection(id) ?: return@mapNotNull null
            Buff(
                id = id,
                name = s.getString("name", id)!!,
                icon = s.getString("icon")?.let { Material.matchMaterial(it) } ?: Material.POTION,
                description = s.getStringList("description"),
                stats = BuffStat.entries.mapNotNull { stat -> if (s.isSet(stat.key)) stat to s.getDouble(stat.key) else null }.toMap(),
            )
        }.associateBy { it.id }
    }
}

enum class BuffStat(val key: String, val label: String, private val percent: Boolean) {
    /** 던전 몬스터에게 주는 피해. */
    ATTACK("attack", "공격력", true),

    /** 던전 몬스터에게 받는 피해를 줄인다(음수면 더 받는다). */
    DEFENSE("defense", "받는 피해 감소", true),

    /** 최대 체력(2 = 하트 하나). */
    MAX_HEALTH("max-health", "최대 체력", false),
    SPEED("speed", "이동 속도", true),
    ATTACK_SPEED("attack-speed", "공격 속도", true),

    /** 보상 상자의 던전 장비 확률. */
    REWARD("reward", "보상", true),
    ;

    fun describe(value: Double): String {
        val good = value >= 0
        val color = if (good) "<green>" else "<red>"
        val number = if (percent) "${if (good) "+" else ""}${(value * 100).toInt()}%" else "${if (good) "+" else ""}${"%.1f".format(value / 2)}칸"
        return "<gray>$label</gray> $color$number"
    }
}
