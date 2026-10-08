package com.inmc.dungeon.run

import com.inmc.dungeon.def.RoomDef
import com.inmc.dungeon.def.RoomKind
import kotlin.random.Random

/**
 * 다음 방 선택지 굴리기 — 순수 계산(`ChoicesTest`). 판의 시드로 만든 [Random] 을 받으므로 같은 시드면 같은 판이 나온다.
 *
 * **보스방 규칙**(사용자 결정 2026-10-08): 길이 최소를 지나면 선택지 하나가 보스방일 수 있고 갈수록 자주,
 * 최대에서는 보스방만 나온다. "한 방 더 돌지, 보스로 갈지"를 파티가 고르게 하려는 것.
 */
object Choices {

    data class Input(
        /** 시작의 방 다음으로 이미 들어간 방 수. 다음 방은 `step + 1` 번째. */
        val step: Int,
        val length: IntRange,
        val weights: Map<RoomKind, Int>,
        val used: Map<RoomKind, Int>,
        val maxPerKind: Map<RoomKind, Int>,
        val eliteFrom: Int,
        /** 방 풀에 실제로 있는 종류. 없는 종류는 굴리지 않는다. */
        val available: Set<RoomKind>,
        /** 선택지 수 = 지금 방의 출구 수(1~3). */
        val slots: Int,
    )

    /** 이번에 보스방이 섞일 확률. 최소 전이면 0, 최대면 1. */
    fun bossChance(step: Int, length: IntRange): Double = when {
        step >= length.last -> 1.0
        step < length.first -> 0.0
        else -> (step - length.first + 1).toDouble() / (length.last - length.first + 1)
    }

    fun kinds(input: Input, random: Random): List<RoomKind> {
        val slots = input.slots.coerceIn(1, 3)
        val bossAvailable = RoomKind.BOSS in input.available
        if (input.step >= input.length.last) return if (bossAvailable) listOf(RoomKind.BOSS) else emptyList()

        val candidates = input.weights.filter { (kind, weight) ->
            weight > 0 && kind.choosable && kind in input.available &&
                (input.maxPerKind[kind]?.let { (input.used[kind] ?: 0) < it } ?: true) &&
                (kind != RoomKind.ELITE || input.step + 1 >= input.eliteFrom)
        }
        val boss = bossAvailable && random.nextDouble() < bossChance(input.step, input.length)
        if (candidates.isEmpty()) return if (bossAvailable) listOf(RoomKind.BOSS) else emptyList()

        val wanted = if (boss) slots - 1 else slots
        val picked = ArrayList<RoomKind>(slots)
        val pool = candidates.toMutableMap()
        // 종류가 겹치지 않게 먼저 뽑고, 모자라면 같은 종류를 또(방은 다른 것으로) 채운다.
        while (picked.size < wanted && pool.isNotEmpty()) {
            val kind = weighted(pool, random)
            picked.add(kind)
            pool.remove(kind)
        }
        while (picked.size < wanted) picked.add(weighted(candidates, random))
        if (boss) picked.add(RoomKind.BOSS)
        return picked
    }

    /**
     * 종류마다 방 하나. 이번 판에 아직 안 나온 방을 먼저 고르고, 같은 선택지 안에서 같은 방이 두 번 나오지 않게 한다.
     * 고를 방이 없으면 null.
     */
    fun pickRoom(kind: RoomKind, pool: List<RoomDef>, usedRooms: Set<String>, taken: Set<String>, random: Random): RoomDef? {
        val ofKind = pool.filter { it.kind == kind && it.id !in taken }
        if (ofKind.isEmpty()) return null
        val fresh = ofKind.filter { it.id !in usedRooms }
        val from = fresh.ifEmpty { ofKind }
        return from[random.nextInt(from.size)]
    }

    /** 비밀방을 어떻게 낼지(사용자 결정 2026-10-08 — 둘 다). */
    sealed interface Secret {
        /** 안 낸다. */
        data object None : Secret

        /** 숨은 `[비밀]` 자리를 찾았다 — 네 번째 출구로 **더한다**. */
        data object Extra : Secret

        /** 확률로 선택지 [index] 하나를 "???"(정체를 숨긴 비밀방)로 **바꾼다**. 보스 칸은 안 바꾼다. */
        data class Hidden(val index: Int) : Secret
    }

    /**
     * [available] = 풀에 비밀방이 있고 이번 판의 비밀방 수가 상한(기본 1) 아래. 찾은 숨은 자리가 먼저, 아니면 [chancePercent] 로.
     */
    fun secret(kinds: List<RoomKind>, available: Boolean, found: Boolean, chancePercent: Double, random: Random): Secret {
        if (!available) return Secret.None
        if (found) return Secret.Extra
        val candidates = kinds.indices.filter { kinds[it] != RoomKind.BOSS }
        if (candidates.isEmpty() || random.nextDouble() * 100.0 >= chancePercent) return Secret.None
        return Secret.Hidden(candidates[random.nextInt(candidates.size)])
    }

    private fun weighted(weights: Map<RoomKind, Int>, random: Random): RoomKind {
        val total = weights.values.sum()
        var roll = random.nextInt(total)
        for ((kind, weight) in weights) {
            if (roll < weight) return kind
            roll -= weight
        }
        return weights.keys.last()
    }
}
