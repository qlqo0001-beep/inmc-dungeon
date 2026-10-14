package com.inmc.dungeon.player

import com.inmc.dungeon.def.Difficulty
import com.inmc.dungeon.def.DungeonDef
import kr.inmc.core.CorePlugin
import java.util.UUID

/**
 * 난이도 해금(`difficulty-mode: choose` + `unlock: true`) — 깬 난이도의 **다음 단계까지** 고를 수 있다.
 * PlayerStore 네임스페이스 `dungeon`, 주제 = 던전 id, 열쇠 `cleared-<난이도 id>` = true. 난이도 순서를 숫자로 적지 않는다 —
 * 관리자가 난이도를 사이에 끼우면 숫자가 다른 난이도를 가리킨다(업적 규칙 4 와 같은 까닭).
 *
 * 모드마다 따로(2026-10-15, core `Realms` — 기능 `dungeon.unlocks`): 네임스페이스 `dungeon@<모드>` — 그 모드에서 깬 것만 그 모드에서 열린다.
 * 같이 쓰기·모드 없음이면 옛 `dungeon` 그대로.
 */
object Unlocks {

    const val NAMESPACE = "dungeon"

    /** core `Realms` 기능 열쇠. */
    const val FEATURE = "dungeon.unlocks"

    private val players get() = CorePlugin.get().players

    private fun ns(player: UUID): String = kr.inmc.core.integration.Realms.key(NAMESPACE, kr.inmc.core.integration.Realms.scope(FEATURE, player))

    fun cleared(player: UUID, dungeon: String, difficulty: String): Boolean =
        players.get(player, ns(player), dungeon, key(difficulty)) == true

    /** [player] 가 [difficulty] 를 고를 수 있는가 — 해금을 안 쓰거나 첫 난이도거나 바로 앞 난이도를 깼으면. */
    fun allowed(player: UUID, dungeon: DungeonDef, difficulty: Difficulty): Boolean {
        if (!dungeon.unlock) return true
        val previous = dungeon.previous(difficulty) ?: return true
        return cleared(player, dungeon.id, previous.id) || cleared(player, dungeon.id, difficulty.id)
    }

    fun record(player: UUID, dungeon: String, difficulty: String) {
        if (!cleared(player, dungeon, difficulty)) players.set(player, ns(player), dungeon, key(difficulty), true)
    }

    fun reset(player: UUID, dungeon: String) {
        players.clearSubject(player, ns(player), dungeon)
    }

    private fun key(difficulty: String) = "cleared-$difficulty"
}
