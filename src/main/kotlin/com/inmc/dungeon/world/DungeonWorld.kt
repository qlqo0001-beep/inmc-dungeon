package com.inmc.dungeon.world

import com.inmc.dungeon.Dungeons
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.GameRules
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.generator.ChunkGenerator
import java.io.File

/**
 * 던전 월드 — **켤 때마다 지우고 새로 만든다**(추천 14). 판은 재시작을 넘어 이어지지 않으므로 지난 판의 방이 남아 있을 까닭이 없고,
 * 크러시 뒤에 무엇이 남았는지 따질 필요도 없어진다(urb 가 고아 블록으로 겪은 일).
 *
 * 지우는 것은 **우리가 만든 표시 파일([MARKER])이 있는 폴더만**이다. 관리자가 이름을 본 월드와 같게 적는 실수로 남의 월드를 지우지 않게 —
 * 표시 없는 같은 이름의 폴더가 있으면 만들지 않고 [ready] 가 false 로 남는다(던전을 열지 않는다).
 */
class DungeonWorld(private val d: Dungeons) {

    @Volatile
    var world: World? = null
        private set

    val ready: Boolean get() = world != null

    fun setup() {
        val name = d.config.worldName
        Bukkit.getWorld(name)?.let { loaded ->
            if (!File(loaded.worldFolder, MARKER).isFile) {
                d.logger.severe("월드 '$name' 이(가) 이미 있고 던전이 만든 월드가 아닙니다 — 던전을 켜지 않습니다. config.yml 의 world.name 을 바꾸세요.")
                return
            }
            for (player in loaded.players) player.teleport(Bukkit.getWorlds().first().spawnLocation)
            Bukkit.unloadWorld(loaded, false)
        }
        for (folder in candidates(name)) {
            if (!folder.exists()) continue
            if (!File(folder, MARKER).isFile) {
                d.logger.severe("폴더 '${folder.path}' 이(가) 있는데 던전이 만든 월드가 아닙니다 — 지우지 않고 던전을 켜지 않습니다. config.yml 의 world.name 을 바꾸세요.")
                return
            }
            if (!folder.deleteRecursively()) d.logger.warning("지난 던전 월드를 다 지우지 못했습니다(${folder.path}) — 남은 파일 위에 새로 만듭니다")
        }
        val created = WorldCreator(name)
            .environment(World.Environment.NORMAL)
            .type(WorldType.FLAT)
            .generateStructures(false)
            .generator(VoidGenerator)
            .createWorld()
        if (created == null) {
            d.logger.severe("던전 월드 '$name' 을(를) 만들지 못했습니다")
            return
        }
        File(created.worldFolder, MARKER).writeText("inmc-dungeon 이 만든 월드입니다. 서버를 켤 때마다 지우고 다시 만듭니다 — 여기에 아무것도 짓지 마세요.\n")
        configure(created)
        world = created
    }

    private fun configure(world: World) {
        for (rule in listOf(
            GameRules.SPAWN_MOBS, GameRules.SPAWN_MONSTERS, GameRules.SPAWN_PATROLS, GameRules.SPAWN_PHANTOMS,
            GameRules.SPAWN_WANDERING_TRADERS, GameRules.SPAWN_WARDENS, GameRules.SPAWNER_BLOCKS_WORK, GameRules.RAIDS,
            GameRules.ADVANCE_TIME, GameRules.ADVANCE_WEATHER, GameRules.MOB_GRIEFING, GameRules.PVP,
            GameRules.SHOW_ADVANCEMENT_MESSAGES, GameRules.SHOW_DEATH_MESSAGES, GameRules.SPECTATORS_GENERATE_CHUNKS,
            GameRules.PROJECTILES_CAN_BREAK_BLOCKS, GameRules.TNT_EXPLODES, GameRules.BLOCK_DROPS, GameRules.SPREAD_VINES,
        )) world.setGameRule(rule, false)
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0)
        // 진짜로 죽는 일은 막지만(쓰러짐), 만에 하나 죽어도 아무것도 잃지 않게.
        world.setGameRule(GameRules.KEEP_INVENTORY, true)
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true)
        world.time = 6000
        world.setStorm(false)
        world.isThundering = false
        world.difficulty = Difficulty.HARD
        world.isAutoSave = false
        world.setSpawnLocation(0, 300, 0)
    }

    /**
     * 지난번 월드 폴더가 있을 수 있는 곳. Paper 26 은 새 월드를 본 월드 안 `dimensions/minecraft/<이름>` 에 둔다(2026-10-08 테섭 확인) —
     * 서버 폴더 바로 아래(옛 방식)도 본다.
     */
    private fun candidates(name: String): List<File> {
        val main = Bukkit.getWorlds().firstOrNull()?.worldFolder
        return listOfNotNull(main?.let { File(it, "dimensions/minecraft/$name") }, File(Bukkit.getWorldContainer(), name)).distinct()
    }

    fun isDungeon(world: World?): Boolean = world != null && world == this.world

    /** 아무것도 만들지 않는 생성기 — 방은 전부 붙여넣는다. */
    object VoidGenerator : ChunkGenerator() {
        override fun shouldGenerateNoise(): Boolean = false
        override fun shouldGenerateSurface(): Boolean = false
        override fun shouldGenerateCaves(): Boolean = false
        override fun shouldGenerateDecorations(): Boolean = false
        override fun shouldGenerateMobs(): Boolean = false
        override fun shouldGenerateStructures(): Boolean = false
    }

    companion object {
        const val MARKER = "inmc-dungeon-world.txt"
    }
}
