package com.inmc.dungeon.config

import org.bukkit.configuration.file.YamlConfiguration

/** `config.yml` 한 벌(불변). `ResourceTest` 가 배포 파일과 기본값이 같은지 지킨다. */
data class DungeonConfig(
    val worldName: String = "inmc_dungeon",
    val baseY: Int = 100,
    val maxActive: Int = 10,
    val cellSpacing: Int = 1024,
    val slotSpacing: Int = 256,
    val voteSeconds: Int = 20,
    val startMaxWait: Int = 300,
    val doorRadius: Double = 1.5,
    val exitDelay: Int = 10,
    val reconnectGrace: Int = 180,
    val blockedCommands: Set<String> = DEFAULT_BLOCKED,
    val portalChance: Double = 0.5,
    val portalWorlds: Set<String> = emptySet(),
    val portalCooldown: Int = 600,
    val portalMax: Int = 5,
    val portalLifetime: Int = 300,
    val portalOwnerSeconds: Int = 60,
    val portalRadius: Double = 1.5,
    val lobbyTimeout: Int = 600,
    val inviteSeconds: Int = 60,
    val recruitCooldown: Int = 30,
) {

    companion object {

        val DEFAULT_BLOCKED = setOf(
            "spawn", "home", "homes", "sethome", "tpa", "tpahere", "tpaccept", "warp", "warps",
            "back", "tp", "tpo", "rtp", "wild", "야생", "스폰", "홈",
        )

        fun from(yaml: YamlConfiguration): DungeonConfig {
            val d = DungeonConfig()
            return DungeonConfig(
                worldName = yaml.getString("world.name", d.worldName)!!.ifBlank { d.worldName },
                baseY = yaml.getInt("world.base-y", d.baseY),
                maxActive = yaml.getInt("instances.max-active", d.maxActive).coerceIn(1, 200),
                cellSpacing = yaml.getInt("instances.cell-spacing", d.cellSpacing).coerceAtLeast(256),
                slotSpacing = yaml.getInt("instances.slot-spacing", d.slotSpacing).coerceAtLeast(64),
                voteSeconds = yaml.getInt("run.vote-seconds", d.voteSeconds).coerceIn(5, 300),
                startMaxWait = yaml.getInt("run.start-max-wait", d.startMaxWait).coerceAtLeast(10),
                doorRadius = yaml.getDouble("run.door-radius", d.doorRadius).coerceIn(0.5, 5.0),
                exitDelay = yaml.getInt("run.exit-delay", d.exitDelay).coerceIn(0, 120),
                reconnectGrace = yaml.getInt("run.reconnect-grace", d.reconnectGrace).coerceAtLeast(0),
                blockedCommands = if (yaml.isList("blocked-commands")) {
                    yaml.getStringList("blocked-commands").map { it.removePrefix("/").lowercase() }.toSet()
                } else d.blockedCommands,
                portalChance = yaml.getDouble("portal.chance", d.portalChance).coerceIn(0.0, 100.0),
                portalWorlds = yaml.getStringList("portal.worlds").toSet(),
                portalCooldown = yaml.getInt("portal.player-cooldown", d.portalCooldown).coerceAtLeast(0),
                portalMax = yaml.getInt("portal.max", d.portalMax).coerceAtLeast(0),
                portalLifetime = yaml.getInt("portal.lifetime", d.portalLifetime).coerceAtLeast(10),
                portalOwnerSeconds = yaml.getInt("portal.owner-seconds", d.portalOwnerSeconds).coerceAtLeast(0),
                portalRadius = yaml.getDouble("portal.radius", d.portalRadius).coerceIn(0.5, 5.0),
                lobbyTimeout = yaml.getInt("lobby.timeout", d.lobbyTimeout).coerceAtLeast(30),
                inviteSeconds = yaml.getInt("lobby.invite-seconds", d.inviteSeconds).coerceAtLeast(5),
                recruitCooldown = yaml.getInt("lobby.recruit-cooldown", d.recruitCooldown).coerceAtLeast(0),
            )
        }
    }
}
