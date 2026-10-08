package com.inmc.dungeon.def

import org.bukkit.configuration.ConfigurationSection

/** 방 안의 칸 하나 — 방의 가장 작은 모서리에서 잰 블록 좌표. */
data class Vec(val x: Int, val y: Int, val z: Int) {
    override fun toString(): String = "$x,$y,$z"

    companion object {
        fun parse(text: String?): Vec? {
            val parts = text?.split(',')?.map { it.trim().toIntOrNull() } ?: return null
            if (parts.size != 3 || parts.any { it == null }) return null
            return Vec(parts[0]!!, parts[1]!!, parts[2]!!)
        }
    }
}

/** 도착 자리 — 바라보는 방향까지. */
data class Spot(val at: Vec, val yaw: Float)

/**
 * 방 안의 자리 표시. 건축물에 놓은 **표지판**으로 찍는다(첫 줄 = 종류, 대괄호 포함 · 둘째 줄 = 덧붙임).
 * 저장할 때 표지판을 읽어 좌표로 바꾸고 붙여넣을 사본에서는 공기로 지운다 — 원본 건축물의 표지판은 그대로라 다시 고쳐 저장하면 된다.
 *
 * | 표지판 | 뜻 |
 * |---|---|
 * | `[도착]` | 이 방에 들어올 때 서는 곳. 표지판 글씨가 보이는 쪽을 바라본다 |
 * | `[출구]` | 다음 방 선택지 하나(홀로그램·문). 셋까지. 둘째 줄 숫자로 순서 |
 * | `[몬스터]` | 몬스터가 나오는 자리. 둘째 줄에 몬스터 id 를 적으면 그 자리는 그 몬스터만 |
 * | `[보스]` | 보스가 나오는 자리 |
 * | `[보상]` | 보상 상자 자리 |
 * | `[URB]` | URB 상자가 나올 수 있는 자리 |
 * | `[이벤트]` | 이벤트가 쓰는 자리 |
 * | `[비밀]` | 숨은 자리 — 누르면 이 판에 네 번째 출구(비밀방)가 열린다. 그 자리가 곧 비밀 출구다. 보이지 않는다 |
 */
data class Markers(
    val arrival: Spot? = null,
    val exits: List<Vec> = emptyList(),
    val mobs: List<MobSpot> = emptyList(),
    val boss: Vec? = null,
    val chest: Vec? = null,
    val urb: List<Vec> = emptyList(),
    val event: List<Vec> = emptyList(),
    val secret: Vec? = null,
) {

    data class MobSpot(val at: Vec, val mob: String? = null)

    fun save(section: ConfigurationSection) {
        arrival?.let { section.set("arrival", "${it.at}|${it.yaw}") }
        section.set("exits", exits.map { it.toString() })
        section.set("mobs", mobs.map { if (it.mob == null) it.at.toString() else "${it.at}|${it.mob}" })
        boss?.let { section.set("boss", it.toString()) }
        chest?.let { section.set("chest", it.toString()) }
        section.set("urb", urb.map { it.toString() })
        section.set("event", event.map { it.toString() })
        secret?.let { section.set("secret", it.toString()) }
    }

    companion object {

        fun load(section: ConfigurationSection?): Markers {
            if (section == null) return Markers()
            val arrival = section.getString("arrival")?.split('|')?.let { parts ->
                Vec.parse(parts[0])?.let { Spot(it, parts.getOrNull(1)?.toFloatOrNull() ?: 0f) }
            }
            return Markers(
                arrival = arrival,
                exits = section.getStringList("exits").mapNotNull(Vec::parse),
                mobs = section.getStringList("mobs").mapNotNull { line ->
                    val parts = line.split('|')
                    Vec.parse(parts[0])?.let { MobSpot(it, parts.getOrNull(1)?.takeIf(String::isNotBlank)) }
                },
                boss = Vec.parse(section.getString("boss")),
                chest = Vec.parse(section.getString("chest")),
                urb = section.getStringList("urb").mapNotNull(Vec::parse),
                event = section.getStringList("event").mapNotNull(Vec::parse),
                secret = Vec.parse(section.getString("secret")),
            )
        }

        /** 표지판 첫 줄 → 표시 종류. 대괄호·공백·대소문자는 가리지 않는다. */
        fun tagOf(line: String): Tag? {
            val key = line.trim().removePrefix("[").removeSuffix("]").trim().lowercase()
            return Tag.entries.firstOrNull { key in it.names }
        }
    }

    enum class Tag(val names: Set<String>) {
        ARRIVAL(setOf("도착", "arrival", "start")),
        EXIT(setOf("출구", "exit", "door")),
        MOB(setOf("몬스터", "몹", "mob")),
        BOSS(setOf("보스", "boss")),
        CHEST(setOf("보상", "chest", "reward")),
        URB(setOf("urb", "랜덤박스")),
        EVENT(setOf("이벤트", "event")),
        SECRET(setOf("비밀", "secret")),
    }

    /** 표지판에서 읽은 것을 모은다. 출구는 둘째 줄 숫자 순(없으면 읽은 순서 뒤로). */
    class Builder {
        private var arrival: Spot? = null
        private val exits = ArrayList<Pair<Int, Vec>>()
        private val mobs = ArrayList<MobSpot>()
        private var boss: Vec? = null
        private var chest: Vec? = null
        private val urb = ArrayList<Vec>()
        private val event = ArrayList<Vec>()
        private var secret: Vec? = null
        val problems = ArrayList<String>()

        fun add(tag: Tag, at: Vec, second: String, yaw: Float) {
            when (tag) {
                Tag.ARRIVAL -> {
                    if (arrival != null) problems.add("[도착] 표지판이 둘 이상 — $at 은(는) 무시")
                    else arrival = Spot(at, yaw)
                }
                Tag.EXIT -> exits.add((second.trim().toIntOrNull() ?: (100 + exits.size)) to at)
                Tag.MOB -> mobs.add(MobSpot(at, second.trim().takeIf(String::isNotEmpty)))
                Tag.BOSS -> if (boss != null) problems.add("[보스] 표지판이 둘 이상 — $at 은(는) 무시") else boss = at
                Tag.CHEST -> if (chest != null) problems.add("[보상] 표지판이 둘 이상 — $at 은(는) 무시") else chest = at
                Tag.URB -> urb.add(at)
                Tag.EVENT -> event.add(at)
                Tag.SECRET -> if (secret != null) problems.add("[비밀] 표지판이 둘 이상 — $at 은(는) 무시") else secret = at
            }
        }

        fun build(): Markers {
            val ordered = exits.sortedBy { it.first }.map { it.second }
            if (ordered.size > MAX_EXITS) problems.add("[출구] 는 ${MAX_EXITS}개까지 — 뒤의 ${ordered.size - MAX_EXITS}개는 무시")
            return Markers(arrival, ordered.take(MAX_EXITS), mobs.toList(), boss, chest, urb.toList(), event.toList(), secret)
        }
    }
}

/** 출구(= 다음 방 선택지) 최대 수. */
const val MAX_EXITS = 3
