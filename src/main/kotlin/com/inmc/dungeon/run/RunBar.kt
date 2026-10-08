package com.inmc.dungeon.run

import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit

/** 판마다 보스바 하나 — 남은 시간·부활·방 번호. 1초마다 [update]. */
object RunBar {

    fun update(run: Run, now: Long) {
        val bar = run.bar ?: BossBar.bossBar(Text.renderFlat(""), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS).also { run.bar = it }
        val left = run.timeLeftMs(now)
        val time = when {
            run.timerStartedAt == null -> "<yellow>준비 중</yellow> <gray>— 출구를 지나면 시간이 흐릅니다</gray>"
            left == null -> "<gray>제한시간 없음</gray>"
            else -> (if (left < 60_000) "<red>" else "<white>") + "⏳ " + Durations.formatShort(left / 1000) + "</white>"
        }
        val room = run.current?.def
        val title = "${run.dungeon.name} <dark_gray>·</dark_gray> ${run.difficulty.name} <dark_gray>·</dark_gray> $time" +
            " <dark_gray>·</dark_gray> <red>♥</red> <white>${run.revivesLeft}</white>" +
            " <dark_gray>·</dark_gray> <gray>${if (room == null || run.step == 0) "시작" else "방 ${run.step}"}</gray>"
        bar.name(Text.renderFlat(title))
        bar.progress(if (left == null || run.timeLimitMs <= 0) 1f else (left.toFloat() / run.timeLimitMs).coerceIn(0f, 1f))
        bar.color(if (left != null && left < 60_000 && run.timerStartedAt != null) BossBar.Color.RED else BossBar.Color.PURPLE)
        for (id in run.members) Bukkit.getPlayer(id)?.showBossBar(bar)
    }

    fun hide(run: Run, player: org.bukkit.entity.Player) {
        run.bar?.let(player::hideBossBar)
    }
}
