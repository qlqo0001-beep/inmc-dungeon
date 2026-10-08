package com.inmc.dungeon.util

import kr.inmc.core.util.TokenBag

/** 메시지 한 번 렌더링에 쓰이는 토큰 주머니. 한글/영문 둘 다 받는다 — 다른 INMC 플러그인들과 같은 관례. */
class Ph : TokenBag<Ph>() {

    override val aliases: Map<String, List<String>> get() = ALIASES

    fun player(name: String): Ph = put(PLAYER, name)

    fun dungeon(name: String): Ph = put(DUNGEON, name)

    fun difficulty(name: String): Ph = put(DIFFICULTY, name)

    fun room(name: String): Ph = put(ROOM, name)

    fun id(text: String): Ph = put(ID, text)

    fun value(text: String): Ph = put(VALUE, text)

    fun count(value: Int): Ph = put(COUNT, value.toString())

    fun amount(text: String): Ph = put(AMOUNT, text)

    companion object {

        fun of(): Ph = Ph()

        const val PLAYER = "player"
        const val DUNGEON = "dungeon"
        const val DIFFICULTY = "difficulty"
        const val ROOM = "room"
        const val ID = "id"
        const val VALUE = "value"
        const val COUNT = "count"
        const val AMOUNT = "amount"

        private val ALIASES: Map<String, List<String>> = mapOf(
            PLAYER to listOf("{플레이어}", "{player}"),
            DUNGEON to listOf("{던전}", "{dungeon}"),
            DIFFICULTY to listOf("{난이도}", "{difficulty}"),
            ROOM to listOf("{방}", "{room}"),
            ID to listOf("{아이디}", "{id}"),
            VALUE to listOf("{값}", "{value}"),
            COUNT to listOf("{개수}", "{count}"),
            AMOUNT to listOf("{수량}", "{amount}"),
        )
    }
}
