package com.inmc.dungeon.def

import org.bukkit.Material

/**
 * 방 종류. 저장 이름은 영문 소문자(`id`) — 명령어 인자·파일에 그대로 쓴다.
 *
 * [fights] = 몬스터를 다 잡아야 문이 열리는 것이 기본인 종류. 방마다 `clear-required` 로 바꿀 수 있다.
 * [choosable] = 다음 방 선택지로 굴러 나올 수 있는 종류. 시작·보스·비밀은 규칙이 따로다(보스는 길이 규칙, 비밀은 5단계).
 */
enum class RoomKind(
    val id: String,
    val label: String,
    val color: String,
    val icon: Material,
    val fights: Boolean,
    val choosable: Boolean,
) {
    START("start", "시작의 방", "<white>", Material.OAK_DOOR, fights = false, choosable = false),
    COMBAT("combat", "전투방", "<red>", Material.IRON_SWORD, fights = true, choosable = true),
    ELITE("elite", "정예 전투방", "<dark_red>", Material.NETHERITE_SWORD, fights = true, choosable = true),
    EVENT("event", "이벤트방", "<light_purple>", Material.ENDER_EYE, fights = false, choosable = true),
    TREASURE("treasure", "보물방", "<gold>", Material.CHEST, fights = false, choosable = true),
    SHOP("shop", "상점방", "<yellow>", Material.EMERALD, fights = false, choosable = true),
    REST("rest", "회복방", "<green>", Material.GOLDEN_APPLE, fights = false, choosable = true),
    BLESSING("blessing", "축복방", "<aqua>", Material.BEACON, fights = false, choosable = true),
    CURSE("curse", "저주방", "<dark_purple>", Material.WITHER_ROSE, fights = false, choosable = true),
    SECRET("secret", "비밀방", "<gray>", Material.SCULK_SHRIEKER, fights = false, choosable = false),
    BOSS("boss", "보스방", "<dark_red><bold>", Material.WITHER_SKELETON_SKULL, fights = true, choosable = false),
    ;

    val display: String get() = "$color$label"

    /** 명령어에서 쓰는 짧은 한글 이름(`/던전 관리 방 저장 <id> 전투`). */
    val word: String get() = label.removeSuffix("의 방").removeSuffix("방").removeSuffix(" 전투").ifEmpty { label }

    companion object {
        fun of(id: String?): RoomKind? = id?.let { key -> entries.firstOrNull { it.id == key.lowercase() || it.name == key.uppercase() } }
    }
}
