package com.inmc.dungeon.item

import com.inmc.dungeon.Dungeons
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.BundleContents
import io.papermc.paper.datacomponent.item.ItemContainerContents
import kr.inmc.core.integration.CarriedStorage
import kr.inmc.core.integration.ExtraInventory
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StoredItem
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * 던전 전용 임시 아이템(사양 20·21절) — 던전이 줄 때 표시(`inmcdungeon:temp`)를 달고, 판에서 나갈 때 **어디에 있든** 지운다(추천 12):
 * 가방·갑옷·왼손·커서 · 꾸러미·셜커 안 · 배낭(`CarriedStorage`) · 장착 칸(`ExtraInventory`). 엔더 상자는 던전에서 막는다.
 * 크러시 뒤 접속할 때도 한 번 훑는다(돌아갈 자리 복원과 같이).
 *
 * 아이템은 `dungeon:<내장>`(아래 넷) 또는 core 아이템 참조(`inmc:`·`minecraft:`·`mmoitems:`)로 만든다. 회복 물약·버프 물약 같은 평범한 소모품은 커스텀아이템·바닐라 그대로.
 */
class TempItems(private val d: Dungeons) {

    val tempKey = NamespacedKey(Dungeons.NAMESPACE, "temp")
    val useKey = NamespacedKey(Dungeons.NAMESPACE, "use")

    enum class Builtin(val id: String, val label: String, val material: Material, val lore: List<String>) {
        REVIVE("revive", "<gold>즉시부활의 깃털</gold>", Material.NETHER_STAR, listOf("<gray>우클릭: 쓰러진 파티원 한 명을 대기 없이 바로 부활</gray>")),
        REVIVE_TICKET("revive_ticket", "<yellow>부활권</yellow>", Material.PAPER, listOf("<gray>우클릭: 파티의 부활 수 +1</gray>")),
        PARTY_HEAL("party_heal", "<green>파티 회복의 열매</green>", Material.GLISTERING_MELON_SLICE, listOf("<gray>우클릭: 살아 있는 파티원 모두 체력 회복</gray>")),
        CLEANSE("cleanse", "<aqua>정화의 수정</aqua>", Material.PRISMARINE_CRYSTALS, listOf("<gray>우클릭: 나에게 붙은 저주를 모두 없앰</gray>")),
        ;

        companion object {
            fun of(id: String?): Builtin? = entries.firstOrNull { it.id == id }
        }
    }

    /** [ref] 로 임시 아이템을 만든다. 만들 수 없으면 null. */
    fun create(ref: String, amount: Int = 1): ItemStack? {
        val stack = if (ref.startsWith("dungeon:")) {
            val builtin = Builtin.of(ref.removePrefix("dungeon:")) ?: return null
            ItemStack(builtin.material, amount).apply {
                editMeta { meta ->
                    meta.displayName(Text.renderFlat(builtin.label))
                    meta.lore(builtin.lore.map(Text::renderFlat))
                    meta.persistentDataContainer.set(useKey, PersistentDataType.STRING, builtin.id)
                }
            }
        } else {
            d.resolver.create(StoredItem(ItemRef.parse(ref), Material.STONE), amount) ?: return null
        }
        return mark(stack)
    }

    /** 던전 전용 표시와 로어 한 줄. */
    fun mark(stack: ItemStack): ItemStack {
        stack.editMeta { meta ->
            meta.persistentDataContainer.set(tempKey, PersistentDataType.BYTE, 1)
            meta.lore(meta.lore().orEmpty() + Text.renderFlat("<dark_gray>던전 전용 — 던전을 나가면 사라집니다</dark_gray>"))
        }
        return stack
    }

    fun isTemp(stack: ItemStack?): Boolean =
        stack != null && !stack.type.isAir && stack.persistentDataContainer.has(tempKey, PersistentDataType.BYTE)

    fun builtinOf(stack: ItemStack?): Builtin? =
        if (stack == null || stack.type.isAir) null else Builtin.of(stack.persistentDataContainer.get(useKey, PersistentDataType.STRING))

    /** [player] 에게서 임시 아이템을 전부 지운다. 지운 칸 수. */
    fun purge(player: Player): Int {
        var removed = 0
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val stack = inventory.getItem(slot) ?: continue
            val (kept, changed) = clean(stack)
            if (changed) {
                inventory.setItem(slot, kept)
                removed++
            }
        }
        val (cursor, cursorChanged) = clean(player.itemOnCursor)
        if (cursorChanged) {
            player.setItemOnCursor(cursor)
            removed++
        }
        for (container in CarriedStorage.containers(player)) {
            val before = container.contents()
            var changed = false
            val after = before.map { stack ->
                if (stack == null) null else clean(stack).also { if (it.second) changed = true }.first
            }
            if (changed) {
                container.write(after)
                removed++
            }
        }
        for (provider in ExtraInventory.providers()) {
            val items = provider.items(player)
            for (index in items.indices.reversed()) {
                if (isTemp(items[index])) {
                    provider.remove(player, index)
                    removed++
                }
            }
        }
        return removed
    }

    /** 임시면 null, 아니면 담긴 것(꾸러미·셜커)에서 임시를 뺀 것. 바뀌었는지와 함께. */
    private fun clean(stack: ItemStack, depth: Int = 0): Pair<ItemStack?, Boolean> {
        if (stack.type.isAir) return stack to false
        if (isTemp(stack)) return null to true
        if (depth >= 4) return stack to false
        var changed = false
        stack.getData(DataComponentTypes.BUNDLE_CONTENTS)?.let { bundle ->
            val inner = bundle.contents().mapNotNull { item -> clean(item, depth + 1).also { if (it.second) changed = true }.first }
            if (changed) stack.setData(DataComponentTypes.BUNDLE_CONTENTS, BundleContents.bundleContents(inner))
        }
        stack.getData(DataComponentTypes.CONTAINER)?.let { container ->
            var innerChanged = false
            val inner = container.contents().map { item ->
                clean(item, depth + 1).also { if (it.second) innerChanged = true }.first ?: ItemStack.empty()
            }
            if (innerChanged) {
                stack.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(inner))
                changed = true
            }
        }
        return stack to changed
    }
}
