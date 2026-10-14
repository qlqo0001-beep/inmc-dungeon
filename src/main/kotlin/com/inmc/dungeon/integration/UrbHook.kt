package com.inmc.dungeon.integration

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.lang.reflect.Method

/**
 * inmc-urb 의 연동 진입점(`UrbPlugin.grant(Player, String)` · `rollForMail(String)` · `boxNames()`)을 리플렉션으로 부른다 — 컴파일 의존 없이(urb 는 선택).
 * 부르는 순간마다 찾는다(적재 순서가 보장되지 않는다). 없거나 실패하면 false/빈 목록.
 */
object UrbHook {

    private fun plugin() = Bukkit.getPluginManager().getPlugin("inmc-urb")?.takeIf { it.isEnabled }

    private fun method(name: String, vararg types: Class<*>): Pair<Any, Method>? {
        val plugin = plugin() ?: return null
        return runCatching { plugin to plugin.javaClass.getMethod(name, *types) }.getOrNull()
    }

    val available: Boolean get() = method("grant", Player::class.java, String::class.java) != null

    /** [box] 상자의 표를 열쇠 없이 굴려 [player] 에게. */
    fun grant(player: Player, box: String): Boolean {
        val (plugin, method) = method("grant", Player::class.java, String::class.java) ?: return false
        return runCatching { method.invoke(plugin, player, box) as? Boolean ?: false }.getOrDefault(false)
    }

    /**
     * [box] 상자의 표를 열쇠 없이 굴려 우편 한 통에 담을 것(아이템 · 명령어)을 돌려받는다(주지도 알리지도 않는다 — 2026-10-10 urb 진입점).
     * 명령어의 받는 사람 자리는 이미 core 우편의 `{player}` 다. 판에 없는 파티원의 몫을 우편으로 보낼 때. 없거나 실패하면 null.
     */
    fun rollForMail(box: String): Pair<List<ItemStack>, List<String>>? {
        val (plugin, method) = method("rollForMail", String::class.java) ?: return null
        return runCatching {
            val rolled = method.invoke(plugin, box) as? Map<*, *> ?: return null
            (rolled["items"] as? List<*>).orEmpty().filterIsInstance<ItemStack>() to
                (rolled["commands"] as? List<*>).orEmpty().filterIsInstance<String>()
        }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    fun boxNames(): List<String>? {
        val (plugin, method) = method("boxNames") ?: return null
        return runCatching { method.invoke(plugin) as? List<String> }.getOrNull()
    }
}
