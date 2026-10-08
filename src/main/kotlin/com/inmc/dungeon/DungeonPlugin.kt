package com.inmc.dungeon

import com.inmc.dungeon.command.DungeonCommand
import com.inmc.dungeon.config.DungeonConfig
import com.inmc.dungeon.config.Messages
import com.inmc.dungeon.listener.GuardListener
import com.inmc.dungeon.listener.MobListener
import com.inmc.dungeon.listener.RunListener
import com.inmc.dungeon.run.RunService
import com.inmc.dungeon.scheduler.Ticker
import kr.inmc.core.event.SignalCatalog
import org.bukkit.plugin.java.JavaPlugin

/**
 * 켜질 때: 설정 → 정의(그 자리에서) → 돌아갈 자리 → 던전 월드(지우고 새로) → 사건·명령 → 티커.
 * 끌 때: 판 안의 사람을 **그 자리에서** 밖으로 — 다음에 켤 때 월드는 새로 만들어진다.
 */
class DungeonPlugin : JavaPlugin() {

    private lateinit var dungeons: Dungeons
    private lateinit var ticker: Ticker

    override fun onEnable() {
        dungeons = Dungeons(this)
        val d = dungeons
        for (name in RESOURCES) d.io.copyDefault(name, d.io.file(name))
        d.config = DungeonConfig.from(d.io.load(d.io.file("config.yml")))
        d.messages = Messages.from(d.io.load(d.io.file("messages.yml")))
        d.runs.cells.max = d.config.maxActive
        d.customItems.setup()
        d.mmoItems.setup()
        d.effects.loadNow()
        d.library.loadNow()
        d.portals.loadNow()
        d.returns.loadNow()
        d.records.loadNow()
        d.world.setup()

        val manager = server.pluginManager
        manager.registerEvents(RunListener(d), this)
        manager.registerEvents(MobListener(d), this)
        manager.registerEvents(GuardListener(d), this)
        manager.registerEvents(com.inmc.dungeon.listener.PortalListener(d), this)
        manager.registerEvents(kr.inmc.core.listener.MenuListener(d), this)
        DungeonCommand(d, this).register()

        // 업적이 "던전 n번 클리어" 를 셀 수 있게. 대상 = 던전 id.
        for ((type, label) in listOf("clear" to "던전 클리어", "fail" to "던전 실패")) {
            SignalCatalog.register(
                RunService.SIGNAL_SOURCE, type, label,
                subjects = { d.library.dungeons.values.map { it.id to it.name } },
                dataKeys = listOf(
                    "dungeon" to "던전 id", "difficulty" to "난이도 id", "party" to "처음 인원",
                    "seconds" to "걸린 시간(초)", "rooms" to "지나온 방 수",
                ),
                description = "$label — 판에 있던 사람마다 한 번",
            )
        }
        SignalCatalog.register(
            RunService.SIGNAL_SOURCE, "room", "던전 방 정리",
            subjects = { com.inmc.dungeon.def.RoomKind.entries.filter { it != com.inmc.dungeon.def.RoomKind.START }.map { it.id to it.label } },
            dataKeys = listOf("dungeon" to "던전 id", "difficulty" to "난이도 id", "room" to "방 id", "step" to "몇 번째 방"),
            description = "던전 방 정리(대상 = 방 종류) — 싸우는 방은 몬스터를 다 잡았을 때, 그 밖의 방은 들어왔을 때. 판에 있던 사람마다",
        )

        ticker = Ticker(d)
        ticker.start()
        logger.info("inmc-dungeon 활성화 완료 - 던전 ${d.library.dungeons.size}개 · 방 ${d.library.rooms.size}개 · 월드 ${if (d.world.ready) d.config.worldName else "없음"}")
    }

    override fun onDisable() {
        if (!::dungeons.isInitialized) return
        if (::ticker.isInitialized) ticker.stop()
        SignalCatalog.unregisterAll(RunService.SIGNAL_SOURCE)
        dungeons.portals.removeAll()
        dungeons.preview.endAll()
        dungeons.runs.shutdown()
        dungeons.io.shutdown()
    }

    /** `/던전 관리 리로드` — 설정·메시지·정의. 열린 판은 이미 읽은 정의로 끝까지 간다. */
    fun reload(then: () -> Unit) {
        val d = dungeons
        d.io.async({ d.io.load(d.io.file("config.yml")) to d.io.load(d.io.file("messages.yml")) }) { (config, messages) ->
            d.config = DungeonConfig.from(config)
            d.messages = Messages.from(messages)
            d.runs.cells.max = d.config.maxActive
            d.portals.loadNow()
            d.effects.loadNow()
            d.library.reload(then)
        }
    }

    private companion object {
        val RESOURCES = listOf("config.yml", "messages.yml", "buffs.yml", "dungeons/example.yml")
    }
}
