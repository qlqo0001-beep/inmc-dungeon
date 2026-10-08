package com.inmc.dungeon.run

import com.inmc.dungeon.Dungeons
import com.inmc.dungeon.def.RoomDef
import kr.inmc.core.util.Text
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType

/**
 * 출구 위의 선택지 홀로그램 + 클릭 판정(Interaction). 전부 **저장하지 않는** 엔티티라 크러시에도 안 남는다(urb 의 교훈).
 * 클릭은 `listener/RunListener` 가 [CHOICE] 표시로 알아본다.
 *
 * 보여 주는 것은 **이번 선택지의 방 정보뿐**(사양 12절) — 그다음 방은 아직 굴리지도 않았다.
 */
class Holograms(private val d: Dungeons) {

    val runKey = NamespacedKey(Dungeons.NAMESPACE, "run")
    val choiceKey = NamespacedKey(Dungeons.NAMESPACE, "choice")

    /** 선택지 [index] 의 홀로그램과 클릭 상자를 [exit] 에 세운다. */
    fun showChoice(run: Run, index: Int, room: RoomDef, exit: Location, votes: Int, hidden: Boolean = false) {
        val text = exit.world.spawn(exit.clone().add(0.0, 2.6, 0.0), TextDisplay::class.java) { display ->
            mark(display, run, index)
            display.text(Text.renderFlat(choiceText(room, votes, decided = false, hidden)))
            display.billboard = Display.Billboard.CENTER
            display.isDefaultBackground = false
            display.backgroundColor = org.bukkit.Color.fromARGB(110, 0, 0, 0)
            display.isSeeThrough = false
            display.isShadowed = true
            display.viewRange = 0.8f
        }
        val box = exit.world.spawn(exit.clone(), Interaction::class.java) { interaction ->
            mark(interaction, run, index)
            interaction.interactionWidth = 1.6f
            interaction.interactionHeight = 2.6f
            interaction.isResponsive = true
        }
        run.displays.add(text)
        run.displays.add(box)
    }

    /** 표 수가 바뀌거나 정해졌을 때 글자만 다시. */
    fun update(run: Run, index: Int, room: RoomDef, votes: Int, decided: Boolean, hidden: Boolean = false) {
        for (entity in run.displays) {
            if (entity !is TextDisplay || choiceOf(entity) != index) continue
            entity.text(Text.renderFlat(choiceText(room, votes, decided, hidden)))
        }
    }

    /** [keep] 말고 다른 선택지의 것을 전부 치운다. null 이면 전부. */
    fun clear(run: Run, keep: Int? = null) {
        val iterator = run.displays.iterator()
        while (iterator.hasNext()) {
            val entity = iterator.next()
            if (keep != null && choiceOf(entity) == keep && entity.isValid) continue
            entity.remove()
            iterator.remove()
        }
    }

    /** 문이 열렸다 — 남은 홀로그램 글자를 바꾼다. */
    fun opened(run: Run, index: Int, room: RoomDef, hidden: Boolean = false) {
        for (entity in run.displays) {
            if (entity !is TextDisplay || choiceOf(entity) != index) continue
            entity.text(Text.renderFlat((if (hidden) HIDDEN_TITLE else room.kind.display) + "\n<green>▶ 문이 열렸습니다 — 들어가세요</green>"))
        }
    }

    fun runOf(entity: Entity): Int? = entity.persistentDataContainer.get(runKey, PersistentDataType.INTEGER)

    fun choiceOf(entity: Entity): Int? = entity.persistentDataContainer.get(choiceKey, PersistentDataType.INTEGER)

    private fun mark(entity: Entity, run: Run, index: Int) {
        entity.isPersistent = false
        entity.persistentDataContainer.set(runKey, PersistentDataType.INTEGER, run.id)
        entity.persistentDataContainer.set(choiceKey, PersistentDataType.INTEGER, index)
    }

    private fun choiceText(room: RoomDef, votes: Int, decided: Boolean, hidden: Boolean): String = buildString {
        if (hidden) {
            // "???" — 비밀방이라는 것도, 무엇이 있는지도 숨긴다.
            append(HIDDEN_TITLE).append("\n<dark_gray>무엇이 있을지 모릅니다</dark_gray>\n")
            if (decided) append("<green>▶ 결정 — 문을 여는 중…</green>")
            else append("<dark_gray>클릭해서 투표 · </dark_gray><yellow>${votes}표</yellow>")
            return@buildString
        }
        append(room.kind.display)
        if (room.name.isNotBlank()) append("\n<white>").append(room.name)
        append("\n<gray>위험도 <yellow>").append("★".repeat(room.risk)).append("<dark_gray>").append("★".repeat(5 - room.risk))
        if (room.kind.fights && room.count.last > 0) {
            append("\n<gray>몬스터 <white>").append(if (room.count.first == room.count.last) "${room.count.first}" else "${room.count.first}~${room.count.last}")
        }
        if (room.reward.isNotBlank()) append("\n<gray>보상 <white>").append(room.reward)
        room.description.take(2).forEach { append("\n<gray>").append(it) }
        append("\n")
        if (decided) append("<green>▶ 결정 — 문을 여는 중…</green>")
        else append("<dark_gray>클릭해서 투표 · </dark_gray><yellow>${votes}표</yellow>")
    }

    private companion object {
        const val HIDDEN_TITLE = "<gray><bold>???</bold></gray>"
    }
}
