package com.inmc.dungeon.scheduler

import com.inmc.dungeon.Dungeons
import kr.inmc.core.scheduler.TickerBase

/** 1초마다 — 판(제한시간·부활·투표 마감·클리어 확인·접속 유예·보스바) · 파티(초대 만료·시간 초과·대기열) · 포탈(수명·글자). 반복 작업은 이것 하나뿐이다. */
class Ticker(private val d: Dungeons) : TickerBase(d.plugin) {

    override val periodTicks: Long = 20L

    override fun ready(): Boolean = d.world.ready

    override fun tick(now: Long) {
        step("판") { d.runs.tick(now) }
        step("파티") { d.lobbies.tick(now) }
        step("포탈") { d.portals.tick(now) }
    }
}
