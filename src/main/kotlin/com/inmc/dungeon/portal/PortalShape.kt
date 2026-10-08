package com.inmc.dungeon.portal

import com.inmc.dungeon.def.Vec
import org.bukkit.configuration.file.YamlConfiguration

/**
 * 포탈 모양 — `portals/<id>.yml`. 블록 하나하나를 디스플레이 엔티티로 세운다(사용자 결정 — 땅에 블록을 놓지 않는다).
 * [entry] = 입구(가까이 가면 입장 화면). 짓는 곳의 `[입구]` 표지판 자리, 없으면 바닥 가운데.
 * 블록 상태는 문자열(`minecraft:oak_stairs[facing=east]`)로 둔다 — 서버 없이 읽고 쓸 수 있게(`Bukkit.createBlockData` 는 세울 때).
 */
data class PortalShape(
    val id: String,
    val blocks: List<Pair<Vec, String>>,
    val entry: Vec,
    val size: Vec,
) {

    fun toYaml(): YamlConfiguration {
        val yaml = YamlConfiguration()
        yaml.options().setHeader(listOf("포탈 모양 '$id' — `/던전 관리 포탈 저장` 으로 만든 것. 블록마다 '좌표|블록 상태'."))
        yaml.set("size", size.toString())
        yaml.set("entry", entry.toString())
        yaml.set("blocks", blocks.map { (at, data) -> "$at|$data" })
        return yaml
    }

    companion object {

        /** 세울 수 있는 블록 수 상한 — 블록마다 엔티티 하나라 크면 무겁다. */
        const val MAX_BLOCKS = 600

        fun from(id: String, yaml: YamlConfiguration): PortalShape? {
            val blocks = yaml.getStringList("blocks").mapNotNull { line ->
                val at = Vec.parse(line.substringBefore('|')) ?: return@mapNotNull null
                val data = line.substringAfter('|', "").ifBlank { return@mapNotNull null }
                at to data
            }
            if (blocks.isEmpty()) return null
            return PortalShape(
                id = id,
                blocks = blocks.take(MAX_BLOCKS),
                entry = Vec.parse(yaml.getString("entry")) ?: Vec(0, 0, 0),
                size = Vec.parse(yaml.getString("size")) ?: Vec(0, 0, 0),
            )
        }

        /**
         * 기본 모양 — 흑요석 틀(가로 5 · 높이 6) 안에 보라 유리. 포탈 모양을 저장하지 않아도 포탈이 선다.
         * 입구는 틀 안 바닥 가운데.
         */
        val DEFAULT: PortalShape = run {
            val blocks = ArrayList<Pair<Vec, String>>()
            for (x in 0..4) for (y in 0..5) {
                val frame = x == 0 || x == 4 || y == 0 || y == 5
                blocks.add(Vec(x, y, 0) to if (frame) "minecraft:crying_obsidian" else "minecraft:purple_stained_glass")
            }
            PortalShape("default", blocks, Vec(2, 1, 0), Vec(5, 6, 1))
        }
    }
}
