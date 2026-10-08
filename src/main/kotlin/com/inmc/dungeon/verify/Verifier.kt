package com.inmc.dungeon.verify

import com.inmc.dungeon.Dungeons
import com.inmc.dungeon.def.RoomKind
import com.inmc.dungeon.integration.UrbHook
import com.inmc.dungeon.util.Ph
import com.inmc.monster.api.MonsterAPI
import org.bukkit.command.CommandSender

/**
 * `/던전 관리 검증` — 정의를 서버 안에서 훑는다(관리자 시험 도구, 추천 17). 판을 열지 않는다.
 * 문제(✘) = 그대로 두면 판이 막히거나 깨진다. 주의(!) = 돌기는 하지만 의도와 다를 것.
 * 붙여넣기 시험은 `/던전 관리 방 시험 <id>` 로 하나씩.
 */
class Verifier(private val d: Dungeons) {

    fun run(sender: CommandSender) {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        if (!d.world.ready) errors.add("던전 월드가 없습니다 — 서버 로그를 보세요(world.name 이 다른 월드와 겹치나?)")
        errors.addAll(d.library.problems)

        val api = MonsterAPI.get()
        if (api == null) errors.add("inmc-monster 가 켜져 있지 않습니다")
        fun mobExists(id: String) = api?.exists(id) ?: true

        val maxSpan = d.config.slotSpacing - 8
        for (room in d.library.rooms.values) {
            val name = "방 ${room.id}(${room.kind.label})"
            val m = room.markers
            if (room.size.x > maxSpan || room.size.z > maxSpan) errors.add("$name: 크기 ${room.size} 가 slot-spacing(${d.config.slotSpacing})에 비해 큼 — 옆 방과 겹친다")
            if (m.arrival == null) warnings.add("$name: [도착] 표지판이 없음 — 방 가운데로 들어간다")
            if (room.kind != RoomKind.BOSS && m.exits.isEmpty()) errors.add("$name: [출구] 표지판이 없음 — 다음 방을 고를 수 없다")
            if (room.kind != RoomKind.BOSS && m.exits.size in 1..2) warnings.add("$name: 출구 ${m.exits.size}개 — 선택지도 ${m.exits.size}개만 나온다")
            if (room.theme.isBlank()) warnings.add("$name: 테마가 비었음 — 어느 던전에도 안 나온다")
            if (room.clearRequired && room.kind.fights) {
                val markerMobs = m.mobs.mapNotNull { it.mob }
                if (room.kind != RoomKind.BOSS && room.count.last <= 0) errors.add("$name: 몬스터 수(monsters.count)가 0 — 싸울 것이 없다")
                if (room.kind != RoomKind.BOSS && room.mobs.isEmpty() && markerMobs.isEmpty()) errors.add("$name: 몬스터 목록(monsters.list)이 비었음")
                if (room.kind != RoomKind.BOSS && m.mobs.isEmpty()) warnings.add("$name: [몬스터] 표지판이 없음 — 도착 자리에서 나온다")
                for (mob in room.mobs.map { it.mob } + markerMobs) if (!mobExists(mob)) errors.add("$name: 몬스터 '$mob' 이(가) 몬스터 플러그인에 없음")
            }
            if (room.kind == RoomKind.BOSS) {
                if (room.boss == null) errors.add("$name: boss(보스 몬스터 id)가 비었음")
                else if (!mobExists(room.boss)) errors.add("$name: 보스 '${room.boss}' 이(가) 몬스터 플러그인에 없음")
                if (m.boss == null) warnings.add("$name: [보스] 표지판이 없음 — 도착 자리에서 나온다")
            }
            if (room.kind == RoomKind.BOSS && m.secret != null) warnings.add("$name: 보스방의 [비밀] 표지판은 쓰이지 않음 — 다음 선택지가 없다")
        }

        for (dungeon in d.library.dungeons.values) {
            val name = "던전 ${dungeon.id}"
            val pool = d.library.pool(dungeon)
            val kinds = pool.map { it.kind }.toSet()
            if (pool.isEmpty()) errors.add("$name: 테마 '${dungeon.theme}' 인 방이 하나도 없음")
            if (RoomKind.START !in kinds) errors.add("$name: 시작의 방이 없음")
            if (RoomKind.BOSS !in kinds) errors.add("$name: 보스방이 없음 — 끝까지 가면 그냥 끝난다")
            if (dungeon.weights.none { (kind, weight) -> weight > 0 && kind in kinds && kind.choosable }) errors.add("$name: 선택지로 나올 방이 없음(가중치가 있는 종류의 방이 풀에 없음)")
            for ((kind, weight) in dungeon.weights) if (weight > 0 && kind.choosable && kind !in kinds) warnings.add("$name: ${kind.label} 가중치가 있지만 그 방이 풀에 없음")
            for (difficulty in dungeon.difficulties) {
                for (affix in difficulty.affixes) if (affix.isBlank()) warnings.add("$name: 난이도 ${difficulty.id} 의 빈 수식어")
                if (difficulty.timeLimit in 1..59) warnings.add("$name: 난이도 ${difficulty.id} 의 제한시간이 ${difficulty.timeLimit}초 — 너무 짧지 않은지")
            }
            if (!dungeon.enabled) warnings.add("$name: 꺼져 있음")

            // 보상 · 상점 · 축복/저주
            val boxes = UrbHook.boxNames()
            val wanted = listOf(dungeon.rewards.treasureBox, dungeon.rewards.bossBox, dungeon.rewards.secretBox) +
                dungeon.difficulties.flatMap { listOf(it.treasureBox, it.bossBox) }
            for (box in wanted.filter(String::isNotEmpty).distinct()) {
                if (boxes == null) warnings.add("$name: 보상 상자 '$box' — inmc-urb 가 없어 확인 못 함(보상이 안 나온다)")
                else if (box !in boxes) errors.add("$name: 보상 상자 '$box' 이(가) inmc-urb 에 없음")
            }
            if (dungeon.rewards.bossBox.isEmpty() && dungeon.rewards.gear.isEmpty() && dungeon.difficulties.all { it.bossBox.isEmpty() }) {
                warnings.add("$name: 보스 보상(rewards.boss · gear)이 비었음 — 보스를 잡아도 상자가 안 나온다")
            }
            if (RoomKind.TREASURE in kinds && dungeon.rewards.treasureBox.isEmpty() && dungeon.difficulties.all { it.treasureBox.isEmpty() }) {
                warnings.add("$name: 보물방이 있는데 rewards.treasure 가 비었음 — 빈 상자가 된다")
            }
            for (gear in dungeon.rewards.gear) if (d.tempItems.create(gear.item) == null) errors.add("$name: 던전 장비 '${gear.item}' 을(를) 만들 수 없음")
            for (offer in dungeon.shop) {
                if (offer.buff.isNotEmpty() && offer.buff !in d.effects.buffs) errors.add("$name: 상점의 축복 '${offer.buff}' 이(가) buffs.yml 에 없음")
                if (offer.item.isNotEmpty() && d.tempItems.create(offer.item) == null) errors.add("$name: 상점 아이템 '${offer.item}' 을(를) 만들 수 없음")
            }
            for (id in dungeon.blessings + dungeon.curses) if (id !in d.effects.buffs) errors.add("$name: 축복/저주 '$id' 이(가) buffs.yml 에 없음")
            if (RoomKind.SHOP in kinds && dungeon.shop.isEmpty()) warnings.add("$name: 상점방이 있는데 shop 이 비었음")
            if (RoomKind.BLESSING in kinds && dungeon.blessings.isEmpty()) warnings.add("$name: 축복방이 있는데 blessings 가 비었음")
            if (RoomKind.CURSE in kinds && dungeon.curses.isEmpty()) warnings.add("$name: 저주방이 있는데 curses 가 비었음")
            // 비밀방 — 숨은 자리([비밀])나 "???" 확률 중 하나는 있어야 나온다.
            val hasSecretSpot = pool.any { it.kind != RoomKind.BOSS && it.markers.secret != null }
            if (RoomKind.SECRET in kinds && !hasSecretSpot && dungeon.secretChance <= 0.0) warnings.add("$name: 비밀방이 있지만 [비밀] 표지판도 secret-chance 도 없음 — 나올 길이 없다")
            if (RoomKind.SECRET !in kinds && hasSecretSpot) warnings.add("$name: [비밀] 표지판이 있는 방이 있지만 비밀방이 풀에 없음 — 눌러도 아무 일 없다")
            if (RoomKind.SECRET in kinds && dungeon.rewards.secretBox.isEmpty() && dungeon.rewards.treasureBox.isEmpty() && dungeon.difficulties.all { it.treasureBox.isEmpty() }) {
                warnings.add("$name: 비밀방이 있는데 rewards.secret · rewards.treasure 가 비었음 — 빈 상자가 된다")
            }
        }

        d.messages.send(sender, "verify-header", Ph.of().count(errors.size).amount(warnings.size.toString()))
        errors.forEach { d.messages.send(sender, "verify-error", Ph.of().value(it)) }
        warnings.forEach { d.messages.send(sender, "verify-warn", Ph.of().value(it)) }
        if (errors.isEmpty() && warnings.isEmpty()) {
            d.messages.send(sender, "verify-ok", Ph.of().count(d.library.dungeons.size).amount(d.library.rooms.size.toString()))
        }
    }
}
