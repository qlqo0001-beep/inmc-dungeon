package com.inmc.dungeon

import com.inmc.dungeon.run.Ballot
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BallotTest {

    private val leader = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val c = UUID.randomUUID()
    private val dd = UUID.randomUUID()
    private val party = setOf(leader, b, c, dd)

    @Test
    fun `혼자면 뽑자마자`() {
        val ballot = Ballot(3, leader, setOf(leader), deadline = 1000)
        assertNull(ballot.result(0, Random(1)))
        ballot.cast(leader, 2)
        assertEquals(2, ballot.result(0, Random(1)))
    }

    @Test
    fun `가장 많은 표`() {
        val ballot = Ballot(3, leader, party, 1000)
        ballot.cast(leader, 0)
        ballot.cast(b, 1)
        ballot.cast(c, 1)
        ballot.cast(dd, 1)
        assertEquals(1, ballot.result(0, Random(1)))
    }

    @Test
    fun `남은 표로 뒤집히지 않으면 시간 전에 정한다`() {
        val ballot = Ballot(3, leader, party, 1000)
        ballot.cast(b, 1)
        ballot.cast(c, 1)
        assertNull(ballot.result(0, Random(1)), "2:0, 남은 2표 — 아직 동률 가능")
        ballot.cast(dd, 1)
        assertEquals(1, ballot.result(0, Random(1)), "3:0, 남은 1표 — 뒤집히지 않는다")
    }

    @Test
    fun `동률이면 파티장이 뽑은 쪽`() {
        val ballot = Ballot(3, leader, party, 1000)
        ballot.cast(leader, 2)
        ballot.cast(b, 2)
        ballot.cast(c, 0)
        ballot.cast(dd, 0)
        repeat(20) { assertEquals(2, ballot.result(0, Random(it))) }
    }

    @Test
    fun `파티장이 동률 후보에 안 뽑았으면 동률 가운데 무작위`() {
        val ballot = Ballot(3, leader, setOf(leader, b, c, dd, UUID.randomUUID()), 1000)
        ballot.cast(leader, 2)
        ballot.cast(b, 0)
        ballot.cast(c, 0)
        ballot.cast(dd, 1)
        val picks = (0 until 50).map { ballot.result(2000, Random(it)) }.toSet()
        assertEquals(setOf(0), picks, "0 이 2표로 혼자 1등")
        ballot.cast(leader, 1) // 0:2, 1:2 — 파티장은 1
        assertEquals(1, ballot.result(2000, Random(5)))
    }

    @Test
    fun `시간이 다 되면 있는 표로 · 아무도 안 뽑으면 전부 가운데 무작위`() {
        val ballot = Ballot(3, leader, party, 1000)
        assertNull(ballot.result(999, Random(1)))
        val picks = (0 until 100).mapNotNull { ballot.result(1000, Random(it)) }.toSet()
        assertEquals(setOf(0, 1, 2), picks)
    }

    @Test
    fun `나간 사람은 기다리지 않는다 · 낸 표는 남는다`() {
        val ballot = Ballot(3, leader, setOf(leader, b), 1000)
        ballot.cast(leader, 1)
        assertNull(ballot.result(0, Random(1)))
        ballot.drop(b)
        assertEquals(1, ballot.result(0, Random(1)))
    }

    @Test
    fun `뽑을 사람이 없으면(모두 쓰러짐) 곧바로 정하지 않고 마감까지 · 부활한 사람은 뽑을 수 있다`() {
        val ballot = Ballot(3, leader, emptySet(), 1000)
        assertNull(ballot.result(0, Random(1)), "표가 없으면 시간 전에는 안 정한다")
        ballot.add(leader)
        assertTrue(ballot.cast(leader, 2))
        assertEquals(2, ballot.result(0, Random(1)))
    }

    @Test
    fun `자격 없는 사람·없는 선택지는 거절 · 다시 내면 바꾼다`() {
        val ballot = Ballot(2, leader, setOf(leader), 1000)
        assertFalse(ballot.cast(b, 0))
        assertFalse(ballot.cast(leader, 5))
        assertTrue(ballot.cast(leader, 0))
        assertTrue(ballot.cast(leader, 1))
        assertEquals(1, ballot.voteOf(leader))
    }

    @Test
    fun `비밀 출구를 찾으면 선택지 하나가 늘고 낸 표·자격은 그대로 · 마감 연장`() {
        val ballot = Ballot(3, leader, setOf(leader, b), 1000)
        assertTrue(ballot.cast(leader, 1))
        assertFalse(ballot.cast(b, 3), "넷째는 아직 없다")
        val next = ballot.expanded(500)
        assertEquals(4, next.options)
        assertEquals(1500, next.deadline)
        assertEquals(1, next.voteOf(leader))
        assertTrue(next.cast(b, 3))
        assertFalse(next.cast(c, 3), "자격 없는 사람은 그대로 없다")
        assertEquals(listOf(0, 1, 0, 1), next.counts().toList())
    }
}
