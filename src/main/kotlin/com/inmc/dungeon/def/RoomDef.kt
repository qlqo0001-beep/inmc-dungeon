package com.inmc.dungeon.def

import org.bukkit.configuration.file.YamlConfiguration

/**
 * 방 하나 — `rooms/<id>.yml` + 같은 이름의 `.schem`(건축물). 방은 **어느 방향·크기로 지어도 된다** — 회전하지 않고 판마다 따로 붙인다(사용자 결정 2026-10-08).
 *
 * 몬스터 수는 [count] 에서 굴린 뒤 난이도·인원 배율을 곱한다(`run/Scaling`). 몬스터는 [mobs] 에서 가중치로 하나씩 뽑고,
 * `[몬스터]` 표지판 둘째 줄에 id 가 적힌 자리는 그 몬스터만 나온다.
 */
data class RoomDef(
    val id: String,
    val kind: RoomKind,
    val theme: String = "",
    /** 홀로그램·안내에 쓰는 이름(MiniMessage). 비면 종류 이름. */
    val name: String = "",
    /** 위험도 1~5(★). */
    val risk: Int = kind.defaultRisk(),
    /** 보상 등급 — 홀로그램에 보이는 글자. */
    val reward: String = "",
    val clearRequired: Boolean = kind.fights,
    val mobs: List<MobEntry> = emptyList(),
    val count: IntRange = 0..0,
    /** 보스방의 보스(몬스터 id). */
    val boss: String? = null,
    /** 건축물 크기(블록). 저장할 때 정해진다. */
    val size: Vec = Vec(0, 0, 0),
    val markers: Markers = Markers(),
    val description: List<String> = emptyList(),
    /** 이벤트방의 이벤트 — `altar`(제단 도박: 축복 또는 저주) · `wave`(몬스터 [waves] 번 버티기). */
    val event: String = "altar",
    val waves: Int = 3,
) {

    data class MobEntry(val mob: String, val weight: Int)

    val displayName: String get() = name.ifBlank { kind.display }

    fun toYaml(): YamlConfiguration {
        val yaml = YamlConfiguration()
        yaml.options().setHeader(listOf("방 '$id' — 건축물은 같은 폴더의 $id.schem. 표시는 저장할 때 표지판에서 읽은 것입니다(손으로 고쳐도 됩니다)."))
        yaml.set("kind", kind.id)
        yaml.set("theme", theme)
        yaml.set("name", name)
        yaml.set("risk", risk)
        yaml.set("reward", reward)
        yaml.set("clear-required", clearRequired)
        yaml.set("monsters.count", if (count.first == count.last) count.first.toString() else "${count.first}~${count.last}")
        yaml.set("monsters.list", mobs.map { "${it.mob}:${it.weight}" })
        boss?.let { yaml.set("boss", it) }
        yaml.set("size", size.toString())
        markers.save(yaml.createSection("markers"))
        yaml.set("description", description)
        if (kind == RoomKind.EVENT) {
            yaml.set("event", event)
            yaml.set("waves", waves)
        }
        return yaml
    }

    companion object {

        private val ID = Regex("^[a-z0-9_-]{1,32}$")

        fun validId(id: String): Boolean = ID.matches(id)

        /** 읽지 못하면 null 과 까닭. */
        fun from(id: String, yaml: YamlConfiguration): Pair<RoomDef?, String?> {
            if (!validId(id)) return null to "id 는 영문 소문자·숫자·_·- 만(1~32자)"
            val kind = RoomKind.of(yaml.getString("kind")) ?: return null to "kind '${yaml.getString("kind")}' 을(를) 모름"
            return RoomDef(
                id = id,
                kind = kind,
                theme = yaml.getString("theme", "")!!.trim().lowercase(),
                name = yaml.getString("name", "")!!,
                risk = yaml.getInt("risk", kind.defaultRisk()).coerceIn(1, 5),
                reward = yaml.getString("reward", "")!!,
                clearRequired = yaml.getBoolean("clear-required", kind.fights),
                mobs = yaml.getStringList("monsters.list").mapNotNull { line ->
                    val parts = line.split(':')
                    val mob = parts[0].trim().ifEmpty { return@mapNotNull null }
                    MobEntry(mob, parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1)
                },
                count = parseRange(yaml.getString("monsters.count")) ?: 0..0,
                boss = yaml.getString("boss")?.trim()?.takeIf(String::isNotEmpty),
                size = Vec.parse(yaml.getString("size")) ?: Vec(0, 0, 0),
                markers = Markers.load(yaml.getConfigurationSection("markers")),
                description = yaml.getStringList("description"),
                event = yaml.getString("event", "altar")!!.trim().lowercase(),
                waves = yaml.getInt("waves", 3).coerceIn(1, 20),
            ) to null
        }

        /** `5` · `5~8` · `5-8`. */
        fun parseRange(text: String?): IntRange? {
            val parts = text?.split('~', '-')?.map { it.trim().toIntOrNull() } ?: return null
            if (parts.isEmpty() || parts.any { it == null }) return null
            val low = parts.first()!!.coerceAtLeast(0)
            val high = (parts.getOrNull(1) ?: low)!!.coerceAtLeast(low)
            return low..high
        }

        private fun RoomKind.defaultRisk(): Int = when (this) {
            RoomKind.BOSS -> 5
            RoomKind.ELITE -> 4
            RoomKind.COMBAT, RoomKind.CURSE -> 3
            RoomKind.EVENT, RoomKind.SECRET -> 2
            else -> 1
        }
    }
}
