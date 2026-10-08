package com.inmc.dungeon

import com.inmc.dungeon.config.DungeonConfig
import com.inmc.dungeon.config.Messages
import com.inmc.dungeon.def.Library
import com.inmc.dungeon.player.Returns
import com.inmc.dungeon.run.Holograms
import com.inmc.dungeon.run.Preview
import com.inmc.dungeon.run.RunService
import com.inmc.dungeon.util.Ph
import com.inmc.dungeon.world.DungeonWorld
import com.inmc.dungeon.world.Schematics
import kr.inmc.core.InmcHost
import kr.inmc.core.config.ConfigService
import kr.inmc.core.util.Placeholders
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin

/**
 * 플러그인을 엮는 서비스 로케이터.
 *
 * 몬스터는 `MonsterAPI` 를 직접 부르고(던전용으로 만들어 둔 API), 방 건축물은 WorldEdit API(`world/Schematics` 한 곳)로 다룬다.
 */
class Dungeons(override val plugin: JavaPlugin) : InmcHost {

    val logger: java.util.logging.Logger = plugin.logger

    override val io = ConfigService(plugin)

    override fun tell(target: CommandSender, key: String, ph: Placeholders?) = messages.send(target, key, ph as? Ph)

    @Volatile
    var config: DungeonConfig = DungeonConfig()

    @Volatile
    var messages: Messages = Messages.from(YamlConfiguration())

    val library = Library(this)

    val world = DungeonWorld(this)

    val schematics = Schematics(this)

    val returns = Returns(this)

    val holograms = Holograms(this)

    val runs = RunService(this)

    val preview = Preview(this)

    val lobbies = com.inmc.dungeon.party.Lobbies(this)

    val customItems = kr.inmc.core.integration.CustomItemHook(logger)

    val mmoItems = kr.inmc.core.integration.MMOItemsHook(logger)

    /** 아이템 참조 → 아이템(바닐라·커스텀아이템·MMOItems). 상점·보상 장비가 쓴다. */
    val resolver = kr.inmc.core.item.ItemResolver(mmoItems, customItems, logger)

    val effects = com.inmc.dungeon.run.Effects(this)

    val tempItems = com.inmc.dungeon.item.TempItems(this)

    val features = com.inmc.dungeon.run.RoomFeatures(this)

    val portals = com.inmc.dungeon.portal.Portals(this)

    val records = com.inmc.dungeon.rank.Records(this)

    companion object {

        /** PDC 네임스페이스 — 플러그인 이름이 바뀌어도 이미 찍힌 표시가 정체를 잃지 않게 상수로 고정한다. */
        const val NAMESPACE = "inmcdungeon"

        const val ADMIN = "inmcdungeon.admin"
        const val PLAY = "inmcdungeon.play"
    }
}
