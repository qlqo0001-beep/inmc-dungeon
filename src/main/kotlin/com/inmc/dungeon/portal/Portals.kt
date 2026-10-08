package com.inmc.dungeon.portal

import com.inmc.dungeon.Dungeons
import com.inmc.dungeon.def.Difficulty
import com.inmc.dungeon.def.DifficultyMode
import com.inmc.dungeon.def.DungeonDef
import com.inmc.dungeon.def.RoomKind
import com.inmc.dungeon.def.Vec
import com.inmc.dungeon.gui.EntryMenu
import com.inmc.dungeon.util.Ph
import kr.inmc.core.integration.TitleForgeNames
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.block.structure.StructureRotation
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 필드 포탈(사양 2절, 사용자 결정 — **디스플레이 엔티티**). 땅에 블록을 놓지 않으니 남의 집·영지·물속에서도 망가뜨릴 것이 없고,
 * 저장하지 않는 엔티티라 크러시 뒤에도 남지 않는다(urb 의 고아 블록 교훈).
 *
 * 생기는 조건(추천 4): 플레이어가 잡은 · 자연 스폰된 적대 몹 · 허용 월드 · 사람마다 쿨타임 · 동시 포탈 수 상한 · 확률.
 * 처음 `owner-seconds` 동안은 발견자(와 그 로비)만 들어간다. 입구에 다가가면 입장 화면이 열린다(들어설 때 한 번 — 나갔다 오면 다시).
 */
class Portals(private val d: Dungeons) {

    class Live(
        val id: Int,
        val dungeon: DungeonDef,
        val difficulty: Difficulty,
        val owner: UUID,
        val ownerName: String,
        val entry: Location,
        val top: Double,
        val expiresAt: Long,
        val ownerUntil: Long,
        val entities: MutableList<Entity> = ArrayList(),
    ) {
        var label: TextDisplay? = null
    }

    private val live = LinkedHashMap<Int, Live>()
    private val cooldowns = HashMap<UUID, Long>()
    private val inside = HashMap<UUID, Int>()
    private var nextId = 1

    @Volatile
    var shapes: Map<String, PortalShape> = emptyMap()
        private set

    val key = NamespacedKey(Dungeons.NAMESPACE, "portal")

    private val dir: File get() = d.io.file("portals")

    fun all(): Collection<Live> = live.values

    fun get(id: Int): Live? = live[id]

    fun loadNow() {
        dir.mkdirs()
        shapes = dir.listFiles { f -> f.extension == "yml" }.orEmpty().mapNotNull { file ->
            PortalShape.from(file.nameWithoutExtension, YamlConfiguration.loadConfiguration(file))
        }.associateBy { it.id }
    }

    fun shapeFor(dungeon: DungeonDef): PortalShape = shapes[dungeon.portal] ?: PortalShape.DEFAULT

    // --- 생기기 --------------------------------------------------------------------

    /** 필드 몹을 잡았다 — 조건이 맞으면 확률로 포탈. */
    fun onKill(player: Player, at: Location) {
        val config = d.config
        if (config.portalChance <= 0.0 || live.size >= config.portalMax) return
        if (d.world.isDungeon(at.world)) return
        if (config.portalWorlds.isNotEmpty() && at.world.name !in config.portalWorlds) return
        if (!player.hasPermission(Dungeons.PLAY) || d.runs.of(player.uniqueId) != null) return
        val now = System.currentTimeMillis()
        if ((cooldowns[player.uniqueId] ?: 0L) > now) return
        if (Math.random() * 100.0 >= config.portalChance) return
        val dungeon = pickDungeon() ?: return
        val difficulty = pickDifficulty(dungeon)
        if (spawn(dungeon, difficulty, player, at) != null) cooldowns[player.uniqueId] = now + config.portalCooldown * 1000L
    }

    /** 포탈로 열 수 있는 던전(켜짐 · 가중치 · 시작의 방·보스방이 있는 것) 가운데 가중치로 하나. */
    private fun pickDungeon(): DungeonDef? {
        val candidates = d.library.dungeons.values.filter { dungeon ->
            dungeon.enabled && dungeon.portalWeight > 0 &&
                d.library.pool(dungeon).map { it.kind }.toSet().let { RoomKind.START in it && RoomKind.BOSS in it }
        }
        return weighted(candidates) { it.portalWeight }
    }

    /** `portal` 이면 난이도의 `portal-weight` 로 굴리고, `choose` 면 첫 난이도(입장 화면에서 고른다). */
    fun pickDifficulty(dungeon: DungeonDef): Difficulty =
        if (dungeon.difficultyMode == DifficultyMode.PORTAL) weighted(dungeon.difficulties) { it.portalWeight } ?: dungeon.difficulties.first()
        else dungeon.difficulties.first()

    private fun <T> weighted(items: List<T>, weight: (T) -> Int): T? {
        val total = items.sumOf { weight(it).coerceAtLeast(0) }
        if (total <= 0) return null
        var roll = (Math.random() * total).toInt()
        for (item in items) {
            val w = weight(item).coerceAtLeast(0)
            if (roll < w) return item
            roll -= w
        }
        return items.last()
    }

    /** [at] 에 포탈을 세운다. 발견자가 바라보는 쪽으로 돌린다. */
    fun spawn(dungeon: DungeonDef, difficulty: Difficulty, owner: Player, at: Location): Live? {
        val shape = shapeFor(dungeon)
        val world = at.world ?: return null
        val base = Location(world, at.blockX.toDouble(), at.blockY.toDouble(), at.blockZ.toDouble())
        val steps = Math.floorMod((owner.location.yaw / 90.0).roundToInt(), 4)
        val rotation = when (steps) {
            1 -> StructureRotation.CLOCKWISE_90
            2 -> StructureRotation.CLOCKWISE_180
            3 -> StructureRotation.COUNTERCLOCKWISE_90
            else -> StructureRotation.NONE
        }
        val now = System.currentTimeMillis()
        val portal = Live(
            id = nextId++, dungeon = dungeon, difficulty = difficulty, owner = owner.uniqueId,
            ownerName = TitleForgeNames.displayName(owner.uniqueId, owner.name),
            entry = base.clone().add(0.5, 0.0, 0.5),
            top = base.y + shape.size.y + 0.4,
            expiresAt = now + d.config.portalLifetime * 1000L,
            ownerUntil = now + d.config.portalOwnerSeconds * 1000L,
        )
        for ((at0, text) in shape.blocks) {
            val data = runCatching { Bukkit.createBlockData(text) }.getOrNull() ?: continue
            data.rotate(rotation)
            val (dx, dz) = rotate(at0.x - shape.entry.x, at0.z - shape.entry.z, steps)
            val spot = base.clone().add(dx.toDouble(), (at0.y - shape.entry.y).toDouble(), dz.toDouble())
            portal.entities.add(world.spawn(spot, BlockDisplay::class.java) {
                mark(it, portal)
                it.block = data
                it.viewRange = 1.5f
            })
        }
        portal.entities.add(world.spawn(portal.entry, Interaction::class.java) {
            mark(it, portal)
            it.interactionWidth = 2.0f
            it.interactionHeight = 3.0f
            it.isResponsive = true
        })
        portal.label = world.spawn(Location(world, portal.entry.x, portal.top, portal.entry.z), TextDisplay::class.java) {
            mark(it, portal)
            it.billboard = Display.Billboard.CENTER
            it.isDefaultBackground = false
            it.backgroundColor = org.bukkit.Color.fromARGB(110, 0, 0, 0)
            it.viewRange = 1.5f
        }.also(portal.entities::add)
        live[portal.id] = portal
        label(portal, now)
        d.messages.send(owner, "portal-found", Ph.of().dungeon(dungeon.name).difficulty(difficulty.name).value(d.config.portalLifetime.toString()))
        world.playSound(portal.entry, org.bukkit.Sound.BLOCK_END_PORTAL_SPAWN, 0.7f, 1.2f)
        return portal
    }

    private fun mark(entity: Entity, portal: Live) {
        entity.isPersistent = false
        entity.persistentDataContainer.set(key, PersistentDataType.INTEGER, portal.id)
    }

    fun portalOf(entity: Entity): Int? = entity.persistentDataContainer.get(key, PersistentDataType.INTEGER)

    fun remove(portal: Live) {
        live.remove(portal.id)
        portal.entities.forEach(Entity::remove)
        inside.values.removeIf { it == portal.id }
    }

    fun removeAll() {
        for (portal in live.values.toList()) remove(portal)
    }

    // --- 들어가기 ------------------------------------------------------------------

    /** 움직일 때 — 입구에 들어서면 입장 화면(들어설 때 한 번). */
    fun onMove(player: Player, to: Location) {
        if (live.isEmpty()) return
        val radius = d.config.portalRadius
        val near = live.values.firstOrNull { it.entry.world == to.world && it.entry.distanceSquared(to) <= radius * radius }
        if (near == null) {
            inside.remove(player.uniqueId)
            return
        }
        if (inside[player.uniqueId] == near.id) return
        inside[player.uniqueId] = near.id
        enter(player, near)
    }

    /** 입장 화면을 연다(입구에 들어섬 · 포탈 클릭). */
    fun enter(player: Player, portal: Live) {
        if (!player.hasPermission(Dungeons.PLAY)) return d.messages.send(player, "no-permission")
        if (d.runs.of(player.uniqueId) != null) return
        val now = System.currentTimeMillis()
        if (now < portal.ownerUntil && player.uniqueId != portal.owner && d.lobbies.of(player.uniqueId)?.leader != portal.owner) {
            d.messages.send(player, "portal-owner-only", Ph.of().player(portal.ownerName).value(((portal.ownerUntil - now) / 1000 + 1).toString()))
            return
        }
        EntryMenu(d, player, portal.dungeon, portal.difficulty, portal.id).show()
    }

    // --- 1초마다 -------------------------------------------------------------------

    fun tick(now: Long) {
        cooldowns.values.removeIf { it < now }
        for (portal in live.values.toList()) {
            // 청크가 내려가면 저장 안 하는 엔티티는 사라진다 — 그 포탈은 끝.
            if (now >= portal.expiresAt || portal.entities.any { !it.isValid }) {
                remove(portal)
                continue
            }
            label(portal, now)
        }
    }

    private fun label(portal: Live, now: Long) {
        val left = Durations.formatShort((portal.expiresAt - now) / 1000)
        val who = if (now < portal.ownerUntil) "<gray>${portal.ownerName} 님만 · ${(portal.ownerUntil - now) / 1000 + 1}초</gray>" else "<gray>누구나</gray>"
        portal.label?.text(Text.renderFlat("<light_purple>던전 포탈</light_purple>\n${portal.dungeon.name} <dark_gray>·</dark_gray> ${portal.difficulty.name}\n$who <dark_gray>·</dark_gray> <gray>$left</gray>"))
    }

    // --- 모양 저장 -----------------------------------------------------------------

    /** 메인 — [world] 의 [min]~[max] 블록을 모양으로. 공기는 빼고 `[입구]` 표지판은 입구로. 안 되면 까닭. */
    fun capture(id: String, world: World, min: Vec, max: Vec): Pair<PortalShape?, String?> {
        val blocks = ArrayList<Pair<Vec, String>>()
        var entry: Vec? = null
        for (x in min.x..max.x) for (y in min.y..max.y) for (z in min.z..max.z) {
            val block = world.getBlockAt(x, y, z)
            if (block.type.isAir) continue
            val rel = Vec(x - min.x, y - min.y, z - min.z)
            val sign = block.state as? Sign
            if (sign != null) {
                val first = PlainTextComponentSerializer.plainText().serialize(sign.getSide(Side.FRONT).line(0))
                if (first.trim().removePrefix("[").removeSuffix("]").trim() in ENTRY_TAGS) {
                    entry = rel
                    continue
                }
            }
            blocks.add(rel to block.blockData.asString)
            if (blocks.size > PortalShape.MAX_BLOCKS) return null to "블록이 ${PortalShape.MAX_BLOCKS}개를 넘음 — 더 작게"
        }
        if (blocks.isEmpty()) return null to "블록이 없음"
        val size = Vec(max.x - min.x + 1, max.y - min.y + 1, max.z - min.z + 1)
        val shape = PortalShape(id, blocks, entry ?: Vec(size.x / 2, 0, size.z / 2), size)
        shapes = shapes + (id to shape)
        val yaml = shape.toYaml()
        d.io.asyncRun { d.io.save(File(dir, "$id.yml"), yaml) }
        return shape to null
    }

    companion object {
        private val ENTRY_TAGS = setOf("입구", "entry")

        /** 위에서 본 시계 방향 90° × [steps] — 마크의 `StructureRotation` 과 같은 방향. */
        fun rotate(x: Int, z: Int, steps: Int): Pair<Int, Int> = when (Math.floorMod(steps, 4)) {
            1 -> -z to x
            2 -> -x to -z
            3 -> z to -x
            else -> x to z
        }
    }
}
