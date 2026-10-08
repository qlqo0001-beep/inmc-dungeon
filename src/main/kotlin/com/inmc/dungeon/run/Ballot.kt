package com.inmc.dungeon.run

import java.util.UUID
import kotlin.random.Random

/**
 * 다음 방 투표 한 번 — 순수 계산(`BallotTest`).
 *
 * 정하는 때(추천 5): 모두 뽑았거나 · 남은 표로 1등이 뒤집히지 않거나 · 시간이 다 됐을 때.
 * 동률이면 파티장이 뽑은 쪽(사양 11절), 파티장이 동률 후보에 표를 안 줬으면 동률 가운데 무작위. 아무도 안 뽑았으면 전부 가운데 무작위.
 */
class Ballot(
    val options: Int,
    private val leader: UUID,
    eligible: Set<UUID>,
    val deadline: Long,
) {

    private val eligible = eligible.toMutableSet()
    private val votes = LinkedHashMap<UUID, Int>()

    /** 뽑을 수 있는 사람이 [option] 에 표를 낸다. 다시 내면 바꾼다. */
    fun cast(voter: UUID, option: Int): Boolean {
        if (voter !in eligible || option !in 0 until options) return false
        votes[voter] = option
        return true
    }

    /** 나가거나 쓰러져 더는 뽑을 수 없게 된 사람. 이미 낸 표는 남긴다. */
    fun drop(voter: UUID) {
        if (voter !in votes) eligible.remove(voter)
    }

    /** 투표 중에 부활하거나 다시 들어온 사람도 뽑을 수 있게. */
    fun add(voter: UUID) {
        eligible.add(voter)
    }

    fun voteOf(voter: UUID): Int? = votes[voter]

    /** 선택지 하나를 더한 새 투표(투표 중에 비밀 출구를 찾았을 때) — 낸 표는 그대로, 마감은 [extraMillis] 늘린다. */
    fun expanded(extraMillis: Long): Ballot {
        val next = Ballot(options + 1, leader, eligible, deadline + extraMillis)
        for ((voter, option) in votes) next.cast(voter, option)
        return next
    }

    fun counts(): IntArray {
        val out = IntArray(options)
        for (option in votes.values) out[option]++
        return out
    }

    /** 정해졌으면 그 선택지, 아직이면 null. */
    fun result(now: Long, random: Random): Int? {
        val counts = counts()
        val remaining = eligible.count { it !in votes }
        val sorted = counts.sortedDescending()
        val lead = sorted[0] - (sorted.getOrNull(1) ?: 0)
        // 표가 하나도 없으면 시간까지 기다린다 — 모두 쓰러져 뽑을 사람이 없을 때 곧바로 무작위로 정하지 않게(부활하면 뽑는다).
        val settled = votes.isNotEmpty() && (remaining == 0 || lead > remaining)
        if (!settled && now < deadline) return null
        val best = counts.max()
        val tied = counts.indices.filter { counts[it] == best }
        if (tied.size == 1) return tied[0]
        votes[leader]?.let { if (it in tied) return it }
        return tied[random.nextInt(tied.size)]
    }
}
