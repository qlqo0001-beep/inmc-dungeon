package com.inmc.dungeon.rank

import com.inmc.dungeon.Dungeons
import kr.inmc.core.rank.Better
import kr.inmc.core.rank.Outcome
import kr.inmc.core.rank.RankBoard
import kr.inmc.core.rank.RankMode
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

/**
 * 최단 클리어 순위(5단계) — **던전 × 난이도마다 하나**(난이도마다 제한시간·몬스터가 달라 시간을 섞으면 안 된다).
 * 클리어한 판에 있던 사람마다 그 판의 시간을 올린다(파티면 모두 같은 시간).
 *
 * core `RankBoard` 만 쓴다 — `RankService` 는 시즌·순위 보상까지라 `RewardHost` 가 필요하고, 여기는 영구 최고 기록 하나면 된다.
 * 같은 시간이면 먼저 세운 사람이 앞(`tiebreak` = 세운 때).
 *
 * **메인 스레드에서만** 고친다. 파일 `ranking.yml` 은 기록할 때마다 워커에서 원자적으로 쓴다(크러시에 깨지지 않게).
 */
class Records(private val d: Dungeons) {

    private val boards = HashMap<String, RankBoard>()

    private val file: File get() = d.io.file("ranking.yml")

    /** 기록 하나를 올린 결과 — [best] 는 이 사람의 이 난이도 최고 기록(초), [rank] 는 지금 순위. */
    data class Result(val personalBest: Boolean, val best: Long, val rank: Int?)

    fun loadNow() {
        boards.clear()
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        val section = yaml.getConfigurationSection("boards") ?: return
        for (dungeon in section.getKeys(false)) {
            val perDungeon = section.getConfigurationSection(dungeon) ?: continue
            for (difficulty in perDungeon.getKeys(false)) {
                boards[key(dungeon, difficulty)] = RankBoard.load(perDungeon.getConfigurationSection(difficulty))
            }
        }
    }

    fun record(dungeon: String, difficulty: String, player: UUID, name: String, seconds: Long, at: Long): Result {
        val board = boards.getOrPut(key(dungeon, difficulty)) { RankBoard() }
        val before = board.entryOf(player)?.takeIf { it.hasRecord }?.best
        board.record(player, name, Outcome(record = seconds, tiebreak = at, score = 0, summary = emptyList()), cleared = true, better = Better.LOWER)
        save()
        val best = board.entryOf(player)?.best ?: seconds
        return Result(personalBest = before == null || seconds < before, best = best, rank = board.rankOf(player, MODE, Better.LOWER))
    }

    fun top(dungeon: String, difficulty: String, limit: Int): List<RankBoard.Entry> =
        boards[key(dungeon, difficulty)]?.top(MODE, Better.LOWER, limit).orEmpty()

    fun entryOf(dungeon: String, difficulty: String, player: UUID): RankBoard.Entry? =
        boards[key(dungeon, difficulty)]?.entryOf(player)?.takeIf { it.hasRecord }

    fun rankOf(dungeon: String, difficulty: String, player: UUID): Int? =
        boards[key(dungeon, difficulty)]?.rankOf(player, MODE, Better.LOWER)

    /** 관리자 초기화 — [difficulty] 가 null 이면 그 던전의 난이도 전부. 지운 순위표 수. */
    fun reset(dungeon: String, difficulty: String?): Int {
        val removed = boards.keys.filter { if (difficulty == null) it.startsWith("$dungeon/") else it == key(dungeon, difficulty) }
        removed.forEach(boards::remove)
        if (removed.isNotEmpty()) save()
        return removed.size
    }

    private fun save() {
        val yaml = YamlConfiguration()
        for ((key, board) in boards) {
            val (dungeon, difficulty) = key.split('/', limit = 2)
            board.save(yaml.createSection("boards.$dungeon.$difficulty"))
        }
        val text = yaml.saveToString()
        val target = file
        d.io.asyncRun { kr.inmc.core.util.AtomicFiles.write(target, text) }
    }

    /** id 는 `[a-z0-9_-]` 라 `/`·`.` 이 들어가지 않는다(YAML 경로로 그대로 써도 된다). */
    private fun key(dungeon: String, difficulty: String) = "$dungeon/$difficulty"

    private companion object {
        val MODE = RankMode.BEST_RECORD
    }
}
