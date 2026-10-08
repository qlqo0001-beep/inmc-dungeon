package com.inmc.dungeon.gui

import com.inmc.dungeon.Dungeons
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * core [kr.inmc.core.gui.Menu] 에 이 플러그인의 로케이터와 보는 사람을 붙인 얇은 층(드랍·상점 `gui/Menu.kt` 와 같은 모양).
 * 리로드가 열린 화면을 닫을 때 [owner] 로 우리 것을 가려낸다. 값 입력은 입력창(Dialog) — [ask].
 */
abstract class Menu(
    protected val d: Dungeons,
    protected val viewer: Player,
    size: Int,
    title: String,
) : kr.inmc.core.gui.Menu(size, Text.renderFlat(title)) {

    override val owner: Any get() = d

    fun show() = open(viewer)

    protected fun close() {
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    protected fun ask(form: DialogForm, reopen: () -> Unit = { show() }, onSubmit: (DialogForm.Values) -> Unit) {
        form.show(d.plugin, viewer, onCancel = { reopen() }) { _, values ->
            onSubmit(values)
            if (viewer.openInventory.topInventory.holder !is kr.inmc.core.gui.Menu) reopen()
        }
    }

    /** 관리자가 아이템이 아닌 재질(물 등)을 아이콘으로 적어도 화면이 깨지지 않게. */
    protected fun safeIcon(material: Material, name: String, lore: List<String>): ItemStack =
        runCatching { Icon.of(material, name, lore) }.getOrElse { Icon.of(Material.ENDER_EYE, name, lore) }

    protected companion object {
        const val SLOT_CLOSE = 53
    }
}
