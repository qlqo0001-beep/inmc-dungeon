package com.inmc.dungeon.gui

import com.inmc.dungeon.Dungeons
import com.inmc.dungeon.def.ShopOffer
import com.inmc.dungeon.util.Ph
import kr.inmc.core.gui.Icon
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * 상점방 — 판 금화(사람마다)로 임시 아이템·축복을 산다(추천 13 — 서버 경제와 따로). 산 아이템은 던전 전용이라 나가면 사라진다.
 */
class ShopMenu(d: Dungeons, viewer: Player, private val runId: Int) :
    Menu(d, viewer, 54, "<dark_gray>던전 상점</dark_gray>") {

    override fun draw() {
        clear()
        val run = d.runs.of(viewer.uniqueId)?.takeIf { it.id == runId }
        if (run == null) {
            viewer.closeInventory()
            return
        }
        val gold = run.goldOf(viewer.uniqueId)
        set(SLOT_GOLD, Icon.of(Material.GOLD_INGOT, "<gold>판 금화 $gold</gold>", "<gray>몬스터를 잡고 방을 정리하면 모입니다.</gray>", "<gray>던전이 끝나면 사라집니다.</gray>"))
        run.dungeon.shop.take(OFFER_SLOTS.size).forEachIndexed { i, offer ->
            set(OFFER_SLOTS[i], icon(offer, gold >= offer.price)) {
                buy(offer)
                refresh()
            }
        }
        if (run.dungeon.shop.isEmpty()) set(22, Icon.of(Material.BARRIER, "<gray>파는 것이 없습니다</gray>", "<dark_gray>던전 정의의 shop 에 적으세요</dark_gray>"))
        close()
    }

    private fun icon(offer: ShopOffer, affordable: Boolean) = run {
        val price = (if (affordable) "<yellow>" else "<red>") + "가격 ${offer.price} 금화"
        if (offer.buff.isNotEmpty()) {
            val buff = d.effects.buffs[offer.buff]
            safeIcon(buff?.icon ?: Material.POTION, buff?.name ?: offer.buff, buff?.lines().orEmpty() + listOf("<gray>판 동안 유지</gray>", "", price))
        } else {
            val sample = d.tempItems.create(offer.item, offer.amount)
            if (sample == null) Icon.of(Material.BARRIER, "<red>${offer.item}</red>", "<gray>만들 수 없는 아이템</gray>")
            else Icon.annotate(sample, null, listOf(price))
        }
    }

    private fun buy(offer: ShopOffer) {
        val run = d.runs.of(viewer.uniqueId)?.takeIf { it.id == runId } ?: return
        val gold = run.goldOf(viewer.uniqueId)
        if (gold < offer.price) return d.messages.send(viewer, "shop-poor", Ph.of().count(offer.price).amount(gold.toString()))
        if (offer.buff.isNotEmpty()) {
            if (!d.effects.give(run, viewer, offer.buff)) return
        } else {
            val stack = d.tempItems.create(offer.item, offer.amount) ?: return
            viewer.inventory.addItem(stack).values.forEach { viewer.world.dropItemNaturally(viewer.location, it) }
        }
        run.gold[viewer.uniqueId] = gold - offer.price
        d.messages.send(viewer, "shop-bought", Ph.of().count(offer.price).amount((gold - offer.price).toString()))
    }

    private companion object {
        const val SLOT_GOLD = 4
        val OFFER_SLOTS = (18..44).toList()
    }
}
