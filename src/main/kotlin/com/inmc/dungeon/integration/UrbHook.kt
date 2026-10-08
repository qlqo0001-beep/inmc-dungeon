package com.inmc.dungeon.integration

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.lang.reflect.Method

/**
 * inmc-urb 의 연동 진입점(`UrbPlugin.grant(Player, String)` · `boxNames()`)을 리플렉션으로 부른다 — 컴파일 의존 없이(urb 는 선택).
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

    @Suppress("UNCHECKED_CAST")
    fun boxNames(): List<String>? {
        val (plugin, method) = method("boxNames") ?: return null
        return runCatching { method.invoke(plugin) as? List<String> }.getOrNull()
    }
}
