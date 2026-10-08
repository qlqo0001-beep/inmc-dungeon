package com.inmc.dungeon.def

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/**
 * 던전 하나 — `dungeons/<id>.yml`. 방 풀은 **같은 테마의 방 전부**다(`rooms/` 의 `theme`).
 *
 * 길이 [length] = 시작의 방과 보스방을 뺀 방 수. 최소를 지나면 보스방이 선택지에 섞여 나오고(갈수록 자주), 최대에서는 보스방만 나온다
 * (사용자 결정 2026-10-08 — `run/Choices`).
 */
data class DungeonDef(
    val id: String,
    val enabled: Boolean = true,
    val name: String = id,
    val theme: String = "",
    val description: List<String> = emptyList(),
    val length: IntRange = 5..8,
    /** 다음 방 선택지로 나올 종류의 가중치. 0 이거나 없으면 안 나온다. */
    val weights: Map<RoomKind, Int> = DEFAULT_WEIGHTS,
    /** 한 판에 나올 수 있는 최대 수. 없으면 제한 없음. */
    val maxPerKind: Map<RoomKind, Int> = emptyMap(),
    /** 정예방은 이 번째 방부터(1 = 첫 방부터). */
    val eliteFrom: Int = 2,
    val difficultyMode: DifficultyMode = DifficultyMode.CHOOSE,
    /** [DifficultyMode.CHOOSE] 에서 파티장이 깬 난이도의 다음 단계까지만 열리게. */
    val unlock: Boolean = true,
    val difficulties: List<Difficulty> = listOf(Difficulty("normal", "<green>노멀")),
    val party: PartyRule = PartyRule(),
    /** 입장 화면 아이콘. */
    val icon: Material = Material.ENDER_EYE,
    /** 입장 화면의 "예상 플레이 시간" 글자. */
    val estimated: String = "",
    /** 입장 화면의 "주요 보상" 줄들. */
    val rewardsText: List<String> = emptyList(),
    /** 필드 포탈 모양(`portals/<id>.yml`). 비우면 기본 모양. */
    val portal: String = "",
    /** 필드에서 포탈이 생길 때 이 던전이 고를 가중치. 0 이면 필드에 안 나온다. */
    val portalWeight: Int = 10,
    val rewards: Rewards = Rewards(),
    val gold: GoldRule = GoldRule(),
    val shop: List<ShopOffer> = emptyList(),
    /** 축복방에서 셋을 굴려 하나를 고르는 축복(`buffs.yml` id). */
    val blessings: List<String> = emptyList(),
    /** 저주방에 들어가면 하나가 붙는 저주. */
    val curses: List<String> = emptyList(),
    /** 저주방 하나마다 보상(장비 확률)에 더하는 비율. */
    val curseReward: Double = 0.3,
    /** 선택지를 굴릴 때 하나가 "???"(비밀방)로 바뀔 확률(%). 비밀방은 방 안 `[비밀]` 자리를 눌러서도 열린다. */
    val secretChance: Double = 10.0,
) {

    fun difficulty(id: String?): Difficulty? = difficulties.firstOrNull { it.id == id }

    /** [difficulty] 바로 앞 난이도(해금 조건). 첫 난이도면 null. */
    fun previous(difficulty: Difficulty): Difficulty? = difficulties.getOrNull(difficulties.indexOf(difficulty) - 1)

    fun toYaml(): YamlConfiguration {
        val yaml = YamlConfiguration()
        yaml.options().setHeader(listOf("던전 '$id'. 방 풀 = rooms/ 에서 theme 이 '$theme' 인 방 전부."))
        yaml.set("enabled", enabled)
        yaml.set("name", name)
        yaml.set("theme", theme)
        yaml.set("description", description)
        yaml.set("length", "${length.first}~${length.last}")
        for ((kind, weight) in weights) yaml.set("weights.${kind.id}", weight)
        for ((kind, max) in maxPerKind) yaml.set("max-per-kind.${kind.id}", max)
        yaml.set("elite-from", eliteFrom)
        yaml.set("difficulty-mode", difficultyMode.id)
        yaml.set("unlock", unlock)
        difficulties.forEachIndexed { i, d -> d.save(yaml.createSection("difficulties.${d.id}"), i) }
        yaml.set("party.min", party.min)
        yaml.set("party.max", party.max)
        yaml.set("party.count-per-extra", party.countPerExtra)
        yaml.set("party.health-per-extra", party.healthPerExtra)
        yaml.set("icon", icon.name.lowercase())
        yaml.set("estimated", estimated)
        yaml.set("rewards-text", rewardsText)
        yaml.set("portal", portal)
        yaml.set("portal-weight", portalWeight)
        yaml.set("rewards.treasure", rewards.treasureBox)
        yaml.set("rewards.boss", rewards.bossBox)
        yaml.set("rewards.gear", rewards.gear.map { mapOf("item" to it.item, "chance" to it.chance) })
        yaml.set("gold.per-kill", gold.perKill)
        yaml.set("gold.per-room", gold.perRoom)
        yaml.set("shop", shop.map { it.toMap() })
        yaml.set("blessings", blessings)
        yaml.set("curses", curses)
        yaml.set("curse-reward", curseReward)
        yaml.set("secret-chance", secretChance)
        yaml.set("rewards.secret", rewards.secretBox)
        return yaml
    }

    companion object {

        val DEFAULT_WEIGHTS: Map<RoomKind, Int> = linkedMapOf(
            RoomKind.COMBAT to 50, RoomKind.ELITE to 15, RoomKind.EVENT to 10, RoomKind.TREASURE to 8,
            RoomKind.SHOP to 5, RoomKind.REST to 6, RoomKind.BLESSING to 4, RoomKind.CURSE to 2,
        )

        fun from(id: String, yaml: YamlConfiguration): Pair<DungeonDef?, String?> {
            if (!RoomDef.validId(id)) return null to "id 는 영문 소문자·숫자·_·- 만(1~32자)"
            val difficulties = yaml.getConfigurationSection("difficulties")?.let { section ->
                section.getKeys(false).mapNotNull { key -> section.getConfigurationSection(key)?.let { Difficulty.load(key, it) } }
                    .sortedBy { it.order }
            }.orEmpty()
            if (difficulties.isEmpty()) return null to "difficulties 가 비었음(난이도가 하나는 있어야 함)"
            return DungeonDef(
                id = id,
                enabled = yaml.getBoolean("enabled", true),
                name = yaml.getString("name", id)!!,
                theme = yaml.getString("theme", "")!!.trim().lowercase(),
                description = yaml.getStringList("description"),
                length = RoomDef.parseRange(yaml.getString("length"))?.let { if (it.first < 1) 1..maxOf(1, it.last) else it } ?: 5..8,
                weights = kindMap(yaml.getConfigurationSection("weights")) ?: DEFAULT_WEIGHTS,
                maxPerKind = kindMap(yaml.getConfigurationSection("max-per-kind")).orEmpty(),
                eliteFrom = yaml.getInt("elite-from", 2).coerceAtLeast(1),
                difficultyMode = DifficultyMode.of(yaml.getString("difficulty-mode")) ?: DifficultyMode.CHOOSE,
                unlock = yaml.getBoolean("unlock", true),
                difficulties = difficulties,
                party = PartyRule(
                    min = yaml.getInt("party.min", 1).coerceIn(1, 20),
                    max = yaml.getInt("party.max", 4).coerceIn(1, 20),
                    countPerExtra = yaml.getDouble("party.count-per-extra", 0.3).coerceAtLeast(0.0),
                    healthPerExtra = yaml.getDouble("party.health-per-extra", 0.25).coerceAtLeast(0.0),
                ),
                // isItem 은 서버 없이 못 부른다(ARCHITECTURE "서버 없이 못 부르는 API") — 아이콘을 그릴 때 거른다.
                icon = yaml.getString("icon")?.let { Material.matchMaterial(it) } ?: Material.ENDER_EYE,
                estimated = yaml.getString("estimated", "")!!,
                rewardsText = yaml.getStringList("rewards-text"),
                portal = yaml.getString("portal", "")!!.trim().lowercase(),
                portalWeight = yaml.getInt("portal-weight", 10).coerceAtLeast(0),
                rewards = Rewards(
                    treasureBox = yaml.getString("rewards.treasure", "")!!.trim(),
                    bossBox = yaml.getString("rewards.boss", "")!!.trim(),
                    secretBox = yaml.getString("rewards.secret", "")!!.trim(),
                    gear = yaml.getMapList("rewards.gear").mapNotNull { m ->
                        val item = m["item"]?.toString()?.trim().orEmpty().ifEmpty { return@mapNotNull null }
                        GearEntry(item, (m["chance"] as? Number)?.toDouble()?.coerceIn(0.0, 100.0) ?: 0.0)
                    },
                ),
                gold = GoldRule(yaml.getInt("gold.per-kill", 2).coerceAtLeast(0), yaml.getInt("gold.per-room", 10).coerceAtLeast(0)),
                shop = yaml.getMapList("shop").mapNotNull(ShopOffer::from),
                blessings = yaml.getStringList("blessings"),
                curses = yaml.getStringList("curses"),
                curseReward = yaml.getDouble("curse-reward", 0.3).coerceAtLeast(0.0),
                secretChance = yaml.getDouble("secret-chance", 10.0).coerceIn(0.0, 100.0),
            ) to null
        }

        private fun kindMap(section: ConfigurationSection?): Map<RoomKind, Int>? = section?.let { s ->
            s.getKeys(false).mapNotNull { key -> RoomKind.of(key)?.let { it to s.getInt(key).coerceAtLeast(0) } }.toMap()
        }
    }
}

enum class DifficultyMode(val id: String) {
    /** 포탈이 생길 때 난이도를 굴린다(난이도마다 `portal-weight`). */
    PORTAL("portal"),

    /** 입장 화면에서 파티장이 고른다. */
    CHOOSE("choose"),
    ;

    companion object {
        fun of(id: String?): DifficultyMode? = entries.firstOrNull { it.id == id?.lowercase() }
    }
}

/**
 * 난이도 하나. 몬스터 배율은 던전이 직접 곱한다 — 체력은 소환할 때 최대 체력에, 주고받는 피해는 몬스터 플러그인의 `CustomMobDamageEvent` 에서.
 * [affixes] 는 몬스터 플러그인의 수식어(정예 같은 것)를 모든 몬스터에 붙인다.
 */
data class Difficulty(
    val id: String,
    val name: String,
    val order: Int = 0,
    /** 제한시간(초). 0 = 없음. */
    val timeLimit: Int = 1200,
    /** 파티가 함께 쓰는 부활 수. */
    val revives: Int = 3,
    /** 쓰러진 뒤 부활까지(초). */
    val reviveDelay: Int = 10,
    val mobCount: Double = 1.0,
    val mobHealth: Double = 1.0,
    val mobDamage: Double = 1.0,
    /** 몬스터가 받는 피해 배율(0.8 = 20% 덜 받음). */
    val mobTaken: Double = 1.0,
    val affixes: List<String> = emptyList(),
    /** [DifficultyMode.PORTAL] 에서 이 난이도로 굴러 나올 가중치. */
    val portalWeight: Int = 10,
    val rewardMultiplier: Double = 1.0,
    /** 이 난이도만의 보물방·보스 보상 상자(비우면 던전의 것). */
    val treasureBox: String = "",
    val bossBox: String = "",
) {

    fun save(section: ConfigurationSection, order: Int) {
        section.set("name", name)
        section.set("order", order)
        section.set("time-limit", timeLimit)
        section.set("revives", revives)
        section.set("revive-delay", reviveDelay)
        section.set("mob-count", mobCount)
        section.set("mob-health", mobHealth)
        section.set("mob-damage", mobDamage)
        section.set("mob-taken", mobTaken)
        section.set("affixes", affixes)
        section.set("portal-weight", portalWeight)
        section.set("reward-multiplier", rewardMultiplier)
        if (treasureBox.isNotEmpty()) section.set("treasure-box", treasureBox)
        if (bossBox.isNotEmpty()) section.set("boss-box", bossBox)
    }

    companion object {
        fun load(id: String, s: ConfigurationSection): Difficulty = Difficulty(
            id = id,
            name = s.getString("name", id)!!,
            order = s.getInt("order", 0),
            timeLimit = s.getInt("time-limit", 1200).coerceAtLeast(0),
            revives = s.getInt("revives", 3).coerceAtLeast(0),
            reviveDelay = s.getInt("revive-delay", 10).coerceAtLeast(0),
            mobCount = s.getDouble("mob-count", 1.0).coerceAtLeast(0.1),
            mobHealth = s.getDouble("mob-health", 1.0).coerceAtLeast(0.1),
            mobDamage = s.getDouble("mob-damage", 1.0).coerceAtLeast(0.0),
            mobTaken = s.getDouble("mob-taken", 1.0).coerceAtLeast(0.0),
            affixes = s.getStringList("affixes"),
            portalWeight = s.getInt("portal-weight", 10).coerceAtLeast(0),
            rewardMultiplier = s.getDouble("reward-multiplier", 1.0).coerceAtLeast(0.0),
            treasureBox = s.getString("treasure-box", "")!!.trim(),
            bossBox = s.getString("boss-box", "")!!.trim(),
        )
    }
}

/** 인원 보정 — 사람이 한 명 늘 때마다 몬스터 수·체력에 더하는 비율. */
data class PartyRule(
    val min: Int = 1,
    val max: Int = 4,
    val countPerExtra: Double = 0.3,
    val healthPerExtra: Double = 0.25,
)

/**
 * 보상(사양 22·23·25절). 상자는 **URB 상자 이름**(inmc-urb 의 표·추첨기를 그대로 — 열쇠 없이 굴린다),
 * 던전 장비는 보스 상자를 열 때 사람마다 굴린다(확률 × 난이도 보상 배율 × (1 + 축복·저주 보너스)).
 */
data class Rewards(
    val treasureBox: String = "",
    val bossBox: String = "",
    /** 비밀방 상자. 비우면 보물방 상자. */
    val secretBox: String = "",
    val gear: List<GearEntry> = emptyList(),
)

/** 아이템 참조(`inmc:<커스텀아이템>` · `minecraft:<재질>` · `mmoitems:<종류>:<id>`)와 확률(%). */
data class GearEntry(val item: String, val chance: Double)

/** 판 금화 — 몬스터를 잡은 사람에게 · 방을 정리하면 모두에게. 판이 끝나면 사라진다(서버 경제와 따로 — 추천 13). */
data class GoldRule(val perKill: Int = 2, val perRoom: Int = 10)

/** 상점방 물건 — 아이템(`dungeon:<내장>` 포함) 또는 축복. */
data class ShopOffer(val item: String = "", val buff: String = "", val amount: Int = 1, val price: Int) {

    fun toMap(): Map<String, Any> = buildMap {
        if (item.isNotEmpty()) put("item", item)
        if (buff.isNotEmpty()) put("buff", buff)
        if (amount != 1) put("amount", amount)
        put("price", price)
    }

    companion object {
        fun from(map: Map<*, *>): ShopOffer? {
            val item = map["item"]?.toString()?.trim().orEmpty()
            val buff = map["buff"]?.toString()?.trim().orEmpty()
            if (item.isEmpty() == buff.isEmpty()) return null
            val price = (map["price"] as? Number)?.toInt()?.coerceAtLeast(0) ?: return null
            return ShopOffer(item, buff, ((map["amount"] as? Number)?.toInt() ?: 1).coerceIn(1, 64), price)
        }
    }
}
