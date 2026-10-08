package com.inmc.dungeon.party

import com.inmc.dungeon.def.Difficulty
import com.inmc.dungeon.def.DungeonDef
import java.util.UUID

/**
 * 던전 전용 파티 — **입장하기 전**의 모임(사양 4절). 판이 열리면 사람들은 판으로 옮겨 가고 로비는 없어진다 —
 * 던전 안의 파티는 판 자신이다(파티장·투표·공유 부활). 판이 끝나면 그대로 해체된다.
 * 서버 파티 시스템과 따로다. 메인 스레드에서만 고친다.
 */
class Lobby(
    val id: Int,
    leader: UUID,
    val dungeon: DungeonDef,
    var difficulty: Difficulty,
    /** 이 로비를 연 포탈(있으면). 포탈이 사라져도 로비는 시간 안에서 유효하다. */
    val portal: Int?,
    val createdAt: Long,
) {

    var leader: UUID = leader

    val members = LinkedHashSet<UUID>().apply { add(leader) }

    /** 초대받은 사람 → 초대가 끝나는 때. */
    val invites = HashMap<UUID, Long>()

    /** 판이 가득 차 자리를 기다리는 중 — 자리가 나면 저절로 들어간다. */
    var queuedAt: Long? = null

    var lastRecruit = 0L

    val full: Boolean get() = members.size >= dungeon.party.max
}
