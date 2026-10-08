package com.inmc.dungeon.run

import kotlin.math.roundToInt

/** 몬스터 수·체력 배율 — 난이도 × 인원 보정(추천 7). 순수 계산(`ScalingTest`). */
object Scaling {

    /** 사람이 한 명 늘 때마다 [perExtra] 만큼 더한다. 1명이면 1. */
    fun party(size: Int, perExtra: Double): Double = 1.0 + perExtra * (size.coerceAtLeast(1) - 1)

    /** 굴린 수 [base] 에 배율을 곱해 반올림. 0 은 0 그대로, 그 밖에는 적어도 1. */
    fun count(base: Int, difficulty: Double, partySize: Int, perExtra: Double): Int {
        if (base <= 0) return 0
        return (base * difficulty * party(partySize, perExtra)).roundToInt().coerceAtLeast(1)
    }

    fun health(difficulty: Double, partySize: Int, perExtra: Double): Double = difficulty * party(partySize, perExtra)
}
