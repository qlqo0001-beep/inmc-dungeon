package com.inmc.dungeon.run

import com.inmc.dungeon.def.Difficulty
import com.inmc.dungeon.def.DungeonDef
import com.inmc.dungeon.def.RoomDef
import com.inmc.dungeon.def.RoomKind
import com.inmc.dungeon.def.Vec
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import java.util.UUID
import kotlin.random.Random

/**
 * 판 하나의 상태. **메인 스레드에서만** 만진다 — 붙여넣기 콜백도 메인으로 돌아온 뒤에 고친다.
 * 판은 저장하지 않는다(재시작을 넘어 이어지지 않는다 — 추천 14). 크러시 뒤에는 돌아갈 자리([com.inmc.dungeon.player.Returns])만 남는다.
 */
class Run(
    val id: Int,
    val dungeon: DungeonDef,
    val difficulty: Difficulty,
    val seed: Long,
    val cell: Int,
    val world: World,
    leader: UUID,
    members: Collection<UUID>,
) {

    val random = Random(seed)

    /** 몬스터 플러그인의 소환 태그 — 이 판의 몬스터를 찾고 치우는 열쇠. */
    val tag: String = "dungeon:$id"

    var leader: UUID = leader

    /** 들어간 순서. 첫 사람이 파티장이 아닐 수 있다(파티장이 나가면 넘어간다). */
    val members = LinkedHashSet(members)

    /** 처음 인원 — 몬스터 수·체력 보정은 이 판 내내 같다(도중에 나가도 줄지 않는다). */
    val startSize: Int = members.size

    var phase: Phase = Phase.PREPARING

    var current: Placed? = null
    var next: Placed? = null

    /** 시작의 방 다음으로 들어간 방 수. */
    var step = 0
    val usedKinds = HashMap<RoomKind, Int>()
    val usedRooms = HashSet<String>()

    /** 시작의 방에 들어온 때(최대 대기 계산). */
    var waitingSince = 0L

    /** 제한시간이 흐르기 시작한 때. null = 아직(시작의 방). */
    var timerStartedAt: Long? = null
    val timeLimitMs: Long = difficulty.timeLimit * 1000L

    var revivesLeft: Int = difficulty.revives

    /** 쓰러진 사람 → 부활할 때(밀리초). null 이면 부활 수가 없어 끝날 때까지 관전. */
    val downed = HashMap<UUID, Long?>()

    /** 부활 직후 무적이 끝나는 때. */
    val graceUntil = HashMap<UUID, Long>()

    /** 접속이 끊긴 사람 → 끊긴 때. */
    val offline = HashMap<UUID, Long>()

    var choice: ChoiceRound? = null

    /** 열린 출구 자리(고른 선택지의 출구). */
    var doorAt: Location? = null

    /** 이 방의 숨은 `[비밀]` 자리를 찾았다 — 다음 선택지에 네 번째 출구(비밀방). */
    var secretFound = false

    /** 다음 선택지 하나를 반드시 "???" 로(`/던전 관리 비밀 숨김`). */
    var forceHidden = false

    /** 관리자 시험 도구(클리어·다음·시간·부활·금화·버프·아이템·비밀·시드)를 쓴 판 — 최단 클리어 순위에 올리지 않는다. */
    var assisted = false

    /** 이 판이 띄운 홀로그램·클릭 엔티티. 방을 옮기거나 끝날 때 치운다. */
    val displays = ArrayList<Entity>()

    var bar: BossBar? = null

    /** 판 금화(사람마다) — 판이 끝나면 사라진다. */
    val gold = HashMap<UUID, Int>()

    /** 사람마다 붙은 축복·저주(`buffs.yml` id). 판에서 나가면 뗀다. */
    val buffs = HashMap<UUID, MutableList<String>>()

    /** 저주방이 더한 보상 보너스(판 전체). */
    var rewardBonus = 0.0

    /** 지금 방의 보상 상자 — 블록 자리 → 연 사람. 방을 떠나거나 판이 끝나면 안 연 사람 몫도 준다. */
    val chests = HashMap<Vec, Chest>()

    /** 웨이브 이벤트방의 남은 웨이브. */
    var wavesLeft = 0

    /** 이 방에서 제단을 쓴 사람. */
    val altarUsed = HashSet<UUID>()

    /** 이 방의 축복 후보(사람마다 셋 — 다시 열어도 같은 셋) · 이미 고른 사람. */
    val blessingOffers = HashMap<UUID, List<String>>()
    val blessed = HashSet<UUID>()

    /** 지금 방의 상호작용 자리(상자·상점·제단) 엔티티 — 방을 떠날 때 치운다. 선택지 홀로그램([displays])과 따로 둔다. */
    val stations = ArrayList<Entity>()

    /** 자리마다 비우는 중인 작업 수 · 비운 뒤에 붙일 것 — 같은 자리에서 비우기와 붙이기가 엇갈리지 않게. */
    val clearing = HashMap<Int, Int>()
    val afterClear = HashMap<Int, MutableList<() -> Unit>>()

    /** 끝(ENDING)에서 밖으로 보낼 때. 보스 상자가 있으면 다 열거나 시간이 다 될 때까지. */
    var closeAt: Long? = null

    fun goldOf(player: UUID): Int = gold[player] ?: 0

    fun addGold(player: UUID, amount: Int) {
        if (amount > 0) gold[player] = goldOf(player) + amount
    }

    /** 끝 처리를 시작한 때 — 끝내기가 두 번 돌지 않게. */
    var endingAt: Long? = null
    var success = false

    fun isMember(player: UUID): Boolean = player in members

    fun alive(player: UUID): Boolean = player in members && player !in downed && player !in offline

    fun timeLeftMs(now: Long): Long? {
        if (timeLimitMs <= 0) return null
        val started = timerStartedAt ?: return timeLimitMs
        return (timeLimitMs - (now - started)).coerceAtLeast(0)
    }

    enum class Phase {
        /** 시작의 방을 붙이는 중. */
        PREPARING,

        /** 방 안 — 싸우는 중이거나 선택지를 기다리는 중. */
        ROOM,

        /** 다음 방 투표. */
        CHOOSING,

        /** 고른 방을 붙이는 중. */
        BUILDING,

        /** 출구가 열렸다 — 지나가면 다 같이 옮긴다. */
        DOOR_OPEN,

        /** 끝(성공·실패) — 곧 밖으로. */
        ENDING,
    }

    /** 붙여 둔 방 하나. [origin] 은 가장 작은 모서리. */
    class Placed(val def: RoomDef, val slot: Int, val origin: Location) {

        fun at(v: Vec): Location = origin.clone().add(v.x + 0.5, v.y.toDouble(), v.z + 0.5)

        val arrival: Location
            get() = def.markers.arrival?.let { at(it.at).apply { yaw = it.yaw } }
                ?: origin.clone().add(def.size.x / 2.0, 1.0, def.size.z / 2.0)

        /** 출구 [index] 자리. 출구 표시가 없는 방은 도착 자리. */
        fun exit(index: Int): Location = def.markers.exits.getOrNull(index)?.let(::at) ?: arrival

        val max: Vec get() = Vec(origin.blockX + def.size.x - 1, origin.blockY + def.size.y - 1, origin.blockZ + def.size.z - 1)

        val min: Vec get() = Vec(origin.blockX, origin.blockY, origin.blockZ)

        fun contains(location: Location, margin: Double = 2.0): Boolean =
            location.world == origin.world &&
                location.x >= origin.x - margin && location.x <= origin.x + def.size.x + margin &&
                location.y >= origin.y - margin && location.y <= origin.y + def.size.y + margin &&
                location.z >= origin.z - margin && location.z <= origin.z + def.size.z + margin
    }

    /** 선택지 하나씩의 방·출구 자리와 투표. [hidden] = 정체를 숨긴 "???" 선택지 번호. */
    class ChoiceRound(val options: List<RoomDef>, var ballot: Ballot, val exits: List<Location>, val hidden: Set<Int> = emptySet())

    /** 보상 상자 하나 — 사람마다 한 번씩(사양 25절 "개인 보상"). [boss] 면 던전 장비도 굴린다. */
    class Chest(val at: Location, val box: String, val boss: Boolean) {
        val opened = HashSet<UUID>()
    }
}
