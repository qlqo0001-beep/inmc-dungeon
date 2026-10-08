package com.inmc.dungeon.run

import com.inmc.dungeon.Dungeons
import com.inmc.dungeon.def.BuffStat
import com.inmc.dungeon.def.RoomKind
import com.inmc.dungeon.def.Vec
import com.inmc.dungeon.gui.BlessingMenu
import com.inmc.dungeon.gui.ShopMenu
import com.inmc.dungeon.integration.UrbHook
import com.inmc.dungeon.util.Ph
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StoredItem
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType

/**
 * 방 종류의 효과(사양 13절) — 들어올 때 · 떠날 때. 상호작용은 전부 **자리(station)** — 블록 디스플레이 + 글자 + 클릭 상자(저장 안 함):
 *
 * | 방 | 효과 |
 * |---|---|
 * | 보물 | 보상 상자(사람마다 한 번 · URB 상자) |
 * | 회복 | 들어오면 모두 체력·배고픔 회복, 해로운 물약 효과 지움 |
 * | 축복 | 사람마다 축복 셋 가운데 하나(화면, 제단을 다시 눌러 열 수 있다) |
 * | 저주 | 들어오면 모두에게 저주 하나 + 판의 보상 보너스 |
 * | 상점 | 판 금화로 임시 아이템·축복을 산다 |
 * | 이벤트 | `altar` 제단 도박(반반 축복·저주) · `wave` 몬스터 몇 번 버티기(`RunService`) |
 * | 보스 | 처치하면 보스 보상 상자(URB 상자 + 던전 장비 굴림) |
 * | 비밀 | 비밀 보상 상자(`rewards.secret`, 없으면 보물 상자) |
 *
 * `[비밀]` 표시가 있는 방에는 **보이지 않는** 클릭 상자만 세운다 — 누르면 `RunService.revealSecret`.
 * 상자는 방을 떠나거나 판이 끝날 때 **안 연 사람 몫도 준다** — 보상을 잃지 않게.
 */
class RoomFeatures(private val d: Dungeons) {

    val stationKey = NamespacedKey(Dungeons.NAMESPACE, "station")

    enum class Station(val id: String) { CHEST("chest"), SHOP("shop"), BLESSING("blessing"), ALTAR("altar"), SECRET("secret") }

    /** 방에 들어온 직후 — 몬스터를 부르기 전. */
    fun onEnter(run: Run, placed: Run.Placed) {
        val def = placed.def
        when (def.kind) {
            RoomKind.TREASURE -> placeChest(run, placed, run.difficulty.treasureBox.ifEmpty { run.dungeon.rewards.treasureBox }, boss = false)
            RoomKind.REST -> rest(run)
            RoomKind.BLESSING -> {
                station(run, placed, Station.BLESSING, Material.ENCHANTING_TABLE, "<aqua>축복의 제단</aqua>\n<gray>클릭해서 축복 고르기</gray>")
                for (id in run.members) Bukkit.getPlayer(id)?.takeIf { run.alive(id) }?.let { openBlessing(run, it) }
            }
            RoomKind.CURSE -> curse(run)
            RoomKind.SHOP -> station(run, placed, Station.SHOP, Material.EMERALD_BLOCK, "<green>상점</green>\n<gray>클릭해서 판 금화로 사기</gray>")
            RoomKind.EVENT -> if (def.event == "altar") {
                station(run, placed, Station.ALTAR, Material.RESPAWN_ANCHOR, "<light_purple>수상한 제단</light_purple>\n<gray>클릭 — 축복 또는 저주(반반)</gray>")
            }
            RoomKind.SECRET -> placeChest(run, placed, run.dungeon.rewards.secretBox.ifEmpty { run.difficulty.treasureBox.ifEmpty { run.dungeon.rewards.treasureBox } }, boss = false)
            else -> Unit
        }
        // 숨은 자리 — 보스방은 다음 선택지가 없고, 이번 판에 비밀방을 더 낼 수 없으면 세우지 않는다.
        val secret = def.markers.secret
        if (secret != null && def.kind != RoomKind.BOSS && d.runs.secretAvailable(run)) {
            run.stations.add(run.world.spawn(placed.at(secret), Interaction::class.java) {
                tag(it, run, Station.SECRET)
                it.interactionWidth = 1.0f
                it.interactionHeight = 1.0f
                it.isResponsive = true
            })
        }
    }

    /** 방을 떠날 때 · 판이 끝날 때 — 안 연 상자를 주고 자리를 치운다. */
    fun onLeave(run: Run) {
        for (chest in run.chests.values.toList()) {
            for (id in run.members) {
                val player = Bukkit.getPlayer(id) ?: continue
                if (id !in chest.opened) claim(run, player, chest, auto = true)
            }
        }
        run.chests.clear()
        run.stations.forEach(Entity::remove)
        run.stations.clear()
        run.altarUsed.clear()
        run.blessingOffers.clear()
        run.blessed.clear()
    }

    /** 보스를 잡았다 — 보스 보상 상자. 상자가 비어 있으면(설정 없음) false. */
    fun placeBossChest(run: Run): Boolean {
        val placed = run.current ?: return false
        val box = run.difficulty.bossBox.ifEmpty { run.dungeon.rewards.bossBox }
        if (box.isEmpty() && run.dungeon.rewards.gear.isEmpty()) return false
        placeChest(run, placed, box, boss = true)
        return true
    }

    // --- 자리 ------------------------------------------------------------------------

    private fun spot(placed: Run.Placed, preferChest: Boolean): Location {
        val markers = placed.def.markers
        val at = (if (preferChest) markers.chest ?: markers.event.firstOrNull() else markers.event.firstOrNull() ?: markers.chest)
        if (at != null) return placed.at(at)
        // 표시가 없으면 도착 자리에서 앞으로 세 칸.
        val arrival = placed.arrival
        return arrival.clone().add(arrival.direction.setY(0).normalize().multiply(3))
    }

    private fun station(run: Run, placed: Run.Placed, type: Station, block: Material, label: String, at: Location = spot(placed, false)) {
        val base = Location(at.world, at.blockX.toDouble(), at.blockY.toDouble(), at.blockZ.toDouble())
        val center = base.clone().add(0.5, 0.0, 0.5)
        run.stations.add(at.world.spawn(base, BlockDisplay::class.java) {
            tag(it, run, type)
            it.block = block.createBlockData()
        })
        run.stations.add(at.world.spawn(center.clone().add(0.0, 1.6, 0.0), TextDisplay::class.java) {
            tag(it, run, type)
            it.text(Text.renderFlat(label))
            it.billboard = Display.Billboard.CENTER
            it.isDefaultBackground = false
            it.backgroundColor = org.bukkit.Color.fromARGB(110, 0, 0, 0)
        })
        run.stations.add(at.world.spawn(center, Interaction::class.java) {
            tag(it, run, type)
            it.interactionWidth = 1.2f
            it.interactionHeight = 1.2f
            it.isResponsive = true
        })
    }

    private fun tag(entity: Entity, run: Run, type: Station) {
        entity.isPersistent = false
        entity.persistentDataContainer.set(stationKey, PersistentDataType.STRING, "${run.id}:${type.id}")
    }

    /** 클릭한 엔티티가 자리면 그 판 번호와 종류. */
    fun stationOf(entity: Entity): Pair<Int, Station>? {
        val raw = entity.persistentDataContainer.get(stationKey, PersistentDataType.STRING) ?: return null
        val run = raw.substringBefore(':').toIntOrNull() ?: return null
        val type = Station.entries.firstOrNull { it.id == raw.substringAfter(':') } ?: return null
        return run to type
    }

    /** 자리를 눌렀다. */
    fun use(player: Player, runId: Int, type: Station, entity: Entity) {
        val run = d.runs.of(player.uniqueId) ?: return
        if (run.id != runId || !run.alive(player.uniqueId)) return
        when (type) {
            Station.CHEST -> {
                val at = entity.location
                val chest = run.chests.values.firstOrNull { it.at.world == at.world && it.at.distanceSquared(at) < 4.0 } ?: return
                claim(run, player, chest, auto = false)
            }
            Station.SHOP -> ShopMenu(d, player, run.id).show()
            Station.BLESSING -> openBlessing(run, player)
            Station.ALTAR -> altar(run, player)
            Station.SECRET -> d.runs.revealSecret(run, player)
        }
    }

    // --- 상자 · 보상 -------------------------------------------------------------------

    private fun placeChest(run: Run, placed: Run.Placed, box: String, boss: Boolean) {
        val at = spot(placed, true)
        val key = Vec(at.blockX, at.blockY, at.blockZ)
        run.chests[key] = Run.Chest(Location(at.world, at.blockX + 0.5, at.blockY.toDouble(), at.blockZ + 0.5), box, boss)
        station(run, placed, Station.CHEST, Material.CHEST, if (boss) "<gold><bold>보스 보상 상자</bold></gold>\n<gray>클릭 — 사람마다 한 번</gray>" else "<gold>보상 상자</gold>\n<gray>클릭 — 사람마다 한 번</gray>", at)
        if (boss) d.runs.tell(run, "boss-chest", Ph.of().value(REWARD_WINDOW_SECONDS.toString()))
        else d.runs.tell(run, "treasure-chest")
    }

    /** [player] 몫을 준다 — URB 상자 굴리기 + (보스면) 던전 장비 굴리기. 한 사람 한 번. */
    fun claim(run: Run, player: Player, chest: Run.Chest, auto: Boolean) {
        if (!chest.opened.add(player.uniqueId)) {
            if (!auto) d.messages.send(player, "chest-already")
            return
        }
        var got = false
        if (chest.box.isNotEmpty()) {
            if (UrbHook.grant(player, chest.box)) got = true
            else d.logger.warning("보상 상자 '${chest.box}' 을(를) 굴리지 못했습니다(inmc-urb 없음 · 상자 없음 · 꺼짐)")
        }
        if (chest.boss) got = rollGear(run, player) || got
        if (!got) d.messages.send(player, "chest-empty")
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 1f, 1f)
        chest.at.world.spawnParticle(Particle.TOTEM_OF_UNDYING, chest.at.clone().add(0.0, 1.0, 0.0), 20, 0.3, 0.4, 0.3, 0.1)
        if (auto) d.messages.send(player, "chest-auto")
    }

    /** 던전 장비 — 확률 × 난이도 보상 배율 × (1 + 저주 보너스 + 행운 축복). 얻은 것이 있으면 true. */
    private fun rollGear(run: Run, player: Player): Boolean {
        val factor = run.difficulty.rewardMultiplier * (1.0 + run.rewardBonus + d.effects.total(run, player.uniqueId, BuffStat.REWARD))
        var any = false
        for (gear in run.dungeon.rewards.gear) {
            if (Math.random() * 100.0 >= gear.chance * factor) continue
            val stack = d.resolver.create(StoredItem(ItemRef.parse(gear.item), Material.STONE), 1) ?: continue
            player.inventory.addItem(stack).values.forEach { player.world.dropItemNaturally(player.location, it) }
            d.messages.send(player, "gear-got", Ph.of().value(Text.plain(stack.effectiveName())))
            any = true
        }
        return any
    }

    // --- 회복 · 저주 · 축복 · 제단 ------------------------------------------------------

    private fun rest(run: Run) {
        for (id in run.members) {
            val player = Bukkit.getPlayer(id) ?: continue
            if (!run.alive(id)) continue
            player.getAttribute(Attribute.MAX_HEALTH)?.value?.let { player.health = it }
            player.foodLevel = 20
            player.saturation = 20f
            for (effect in player.activePotionEffects) if (!effect.type.isInstant && effect.type.category == org.bukkit.potion.PotionEffectTypeCategory.HARMFUL) player.removePotionEffect(effect.type)
        }
        d.runs.tell(run, "rest")
    }

    private fun curse(run: Run) {
        val pool = run.dungeon.curses.filter { it in d.effects.buffs }
        if (pool.isEmpty()) return
        val curse = pool[run.random.nextInt(pool.size)]
        for (id in run.members) Bukkit.getPlayer(id)?.let { d.effects.give(run, it, curse) }
        run.rewardBonus += run.dungeon.curseReward
        d.runs.tell(run, "cursed", Ph.of().value(d.effects.buffs[curse]?.name ?: curse).amount("${(run.dungeon.curseReward * 100).toInt()}%"))
    }

    fun openBlessing(run: Run, player: Player) {
        if (player.uniqueId in run.blessed) return d.messages.send(player, "blessing-done")
        val offers = run.blessingOffers.getOrPut(player.uniqueId) {
            run.dungeon.blessings.filter { it in d.effects.buffs }.shuffled(run.random).take(3)
        }
        if (offers.isEmpty()) return d.messages.send(player, "blessing-none")
        BlessingMenu(d, player, run.id, offers).show()
    }

    fun bless(run: Run, player: Player, buffId: String) {
        if (player.uniqueId in run.blessed || buffId !in run.blessingOffers[player.uniqueId].orEmpty()) return
        run.blessed.add(player.uniqueId)
        d.effects.give(run, player, buffId)
        d.messages.send(player, "blessed", Ph.of().value(d.effects.buffs[buffId]?.name ?: buffId))
    }

    private fun altar(run: Run, player: Player) {
        if (!run.altarUsed.add(player.uniqueId)) return d.messages.send(player, "altar-used")
        val good = Math.random() < 0.5
        val pool = (if (good) run.dungeon.blessings else run.dungeon.curses).filter { it in d.effects.buffs }
        if (pool.isEmpty()) return d.messages.send(player, "altar-nothing")
        val id = pool.random()
        d.effects.give(run, player, id)
        player.playSound(player.location, if (good) Sound.BLOCK_BEACON_POWER_SELECT else Sound.ENTITY_WITHER_AMBIENT, 1f, 1f)
        d.messages.send(player, if (good) "altar-good" else "altar-bad", Ph.of().value(d.effects.buffs[id]?.name ?: id))
    }

    /** 내장 아이템 쓰기(우클릭). 썼으면 true — 하나 줄인다. */
    fun useItem(run: Run, player: Player, builtin: com.inmc.dungeon.item.TempItems.Builtin): Boolean {
        when (builtin) {
            com.inmc.dungeon.item.TempItems.Builtin.REVIVE -> {
                val target = run.downed.keys.firstOrNull { Bukkit.getPlayer(it) != null } ?: return false.also { d.messages.send(player, "item-no-downed") }
                d.runs.reviveNow(run, target)
            }
            com.inmc.dungeon.item.TempItems.Builtin.REVIVE_TICKET -> {
                // 끝날 때까지 관전 중인 사람이 있으면 그 사람의 부활로 바로 쓴다.
                val waiting = run.downed.entries.firstOrNull { it.value == null }?.key
                if (waiting != null) run.downed[waiting] = System.currentTimeMillis() + run.difficulty.reviveDelay * 1000L
                else run.revivesLeft++
                d.runs.tell(run, "item-revive-ticket", Ph.of().player(player.name).count(run.revivesLeft))
            }
            com.inmc.dungeon.item.TempItems.Builtin.PARTY_HEAL -> {
                for (id in run.members) {
                    val member = Bukkit.getPlayer(id)?.takeIf { run.alive(id) } ?: continue
                    member.getAttribute(Attribute.MAX_HEALTH)?.value?.let { member.health = it }
                    member.world.spawnParticle(Particle.HEART, member.location.clone().add(0.0, 2.0, 0.0), 5, 0.3, 0.3, 0.3)
                }
                d.runs.tell(run, "item-party-heal", Ph.of().player(player.name))
            }
            com.inmc.dungeon.item.TempItems.Builtin.CLEANSE -> {
                val list = run.buffs[player.uniqueId] ?: return false.also { d.messages.send(player, "item-no-curse") }
                if (!list.removeAll { it in run.dungeon.curses }) return false.also { d.messages.send(player, "item-no-curse") }
                d.effects.apply(run, player)
                d.messages.send(player, "item-cleansed")
            }
        }
        return true
    }

    companion object {
        /** 보스 상자를 열 시간(초) — 다 열면 그보다 일찍 나간다. 안 연 사람 몫은 나갈 때 준다. */
        const val REWARD_WINDOW_SECONDS = 60
    }
}
