package com.inmc.dungeon.world

import com.inmc.dungeon.Dungeons
import com.inmc.dungeon.def.Markers
import com.inmc.dungeon.def.Vec
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.function.operation.ForwardExtentCopy
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.function.pattern.Pattern
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.regions.Region
import com.sk89q.worldedit.session.ClipboardHolder
import com.sk89q.worldedit.world.block.BlockTypes
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Rotatable
import org.bukkit.block.sign.Side
import org.bukkit.entity.Player
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.atan2

/**
 * 방 건축물 ↔ `.schem` — **WorldEdit API 를 부르는 유일한 곳**(테섭은 FastAsyncWorldEdit).
 *
 * - 저장([capture]): 표지판을 읽어 표시로 바꾸고, 사본에서 그 표지판을 공기로 지운 뒤 파일로. 블록 복사는 메인, 파일 쓰기는 워커.
 * - 붙이기([paste])·비우기([clear]): 워커에서 FAWE 로(메인을 멈추지 않는다). 끝나면 메인에서 콜백.
 *   붙일 때마다 파일을 새로 읽는다 — 여러 판이 같은 방을 동시에 붙일 수 있고, 클립보드를 스레드끼리 나눠 쓰지 않으려고.
 */
class Schematics(private val d: Dungeons) {

    /** 저장 결과. [problems] 는 막지는 않지만 알릴 것(표지판 중복 등). */
    data class Captured(val size: Vec, val markers: Markers, val problems: List<String>)

    /** 플레이어의 WorldEdit 선택(가장 작은·큰 모서리). 선택이 없거나 다른 월드면 null. */
    fun selection(player: Player): Pair<Vec, Vec>? = runCatching {
        val session = WorldEdit.getInstance().sessionManager.get(BukkitAdapter.adapt(player))
        val world = session.selectionWorld ?: return null
        if (BukkitAdapter.adapt(world) != player.world) return null
        val region = session.getSelection(world)
        val min = region.minimumPoint
        val max = region.maximumPoint
        Vec(min.x(), min.y(), min.z()) to Vec(max.x(), max.y(), max.z())
    }.getOrNull()

    /** 메인. [world] 의 상자 [min]~[max] 를 [file] 로. 끝나면 메인에서 [then](실패면 null 과 까닭). */
    fun capture(world: World, min: Vec, max: Vec, file: File, then: (Captured?, String?) -> Unit) {
        val builder = Markers.Builder()
        val signs = ArrayList<BlockVector3>()
        for (sign in signsIn(world, min, max)) {
            val front = lines(sign, Side.FRONT)
            val side = if (Markers.tagOf(front.firstOrNull().orEmpty()) != null) front else lines(sign, Side.BACK)
            val tag = Markers.tagOf(side.firstOrNull().orEmpty()) ?: continue
            builder.add(tag, Vec(sign.x - min.x, sign.y - min.y, sign.z - min.z), side.getOrNull(1).orEmpty(), yawOf(sign))
            signs.add(BlockVector3.at(sign.x, sign.y, sign.z))
        }
        val clipboard = try {
            copy(world, min, max, signs)
        } catch (e: Throwable) {
            then(null, "건축물을 복사하지 못했습니다: ${e.message}")
            return
        }
        val markers = builder.build()
        val size = Vec(max.x - min.x + 1, max.y - min.y + 1, max.z - min.z + 1)
        Bukkit.getScheduler().runTaskAsynchronously(d.plugin, Runnable {
            val error = runCatching {
                file.parentFile.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                FileOutputStream(temp).use { out -> BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(out).use { it.write(clipboard) } }
                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }.exceptionOrNull()
            runCatching { clipboard.close() }
            Bukkit.getScheduler().runTask(d.plugin, Runnable {
                if (error != null) then(null, "파일을 쓰지 못했습니다: ${error.message}") else then(Captured(size, markers, builder.problems), null)
            })
        })
    }

    /** 워커에서 [file] 을 [world] 의 [at](가장 작은 모서리)에 붙인다. 끝나면 메인에서 [then](실패면 까닭). */
    fun paste(file: File, world: World, at: Vec, then: (String?) -> Unit) {
        Bukkit.getScheduler().runTaskAsynchronously(d.plugin, Runnable {
            val error = runCatching {
                val format = ClipboardFormats.findByFile(file) ?: error("건축물 형식을 모름: ${file.name}")
                val clipboard: Clipboard = FileInputStream(file).use { input -> format.getReader(input).use { it.read() } }
                try {
                    WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world)).use { session ->
                        val operation = ClipboardHolder(clipboard).createPaste(session)
                            .to(BlockVector3.at(at.x, at.y, at.z))
                            .ignoreAirBlocks(false)
                            .copyEntities(true)
                            .copyBiomes(false)
                            .build()
                        Operations.complete(operation)
                    }
                } finally {
                    runCatching { clipboard.close() }
                }
            }.exceptionOrNull()
            Bukkit.getScheduler().runTask(d.plugin, Runnable { then(error?.let { it.message ?: it.javaClass.simpleName }) })
        })
    }

    /** 워커에서 [min]~[max] 를 공기로. 엔티티는 부른 쪽이 메인에서 먼저 치운다. */
    fun clear(world: World, min: Vec, max: Vec, then: (String?) -> Unit) {
        Bukkit.getScheduler().runTaskAsynchronously(d.plugin, Runnable {
            val error = runCatching<Unit> {
                val weWorld = BukkitAdapter.adapt(world)
                val region = CuboidRegion(weWorld, BlockVector3.at(min.x, min.y, min.z), BlockVector3.at(max.x, max.y, max.z))
                val air: Pattern = BlockTypes.AIR!!.defaultState
                WorldEdit.getInstance().newEditSession(weWorld).use { session -> session.setBlocks(region as Region, air) }
            }.exceptionOrNull()
            Bukkit.getScheduler().runTask(d.plugin, Runnable { then(error?.let { it.message ?: it.javaClass.simpleName }) })
        })
    }

    private fun copy(world: World, min: Vec, max: Vec, erase: List<BlockVector3>): Clipboard {
        val weWorld = BukkitAdapter.adapt(world)
        val region = CuboidRegion(weWorld, BlockVector3.at(min.x, min.y, min.z), BlockVector3.at(max.x, max.y, max.z))
        val clipboard = BlockArrayClipboard(region)
        clipboard.origin = region.minimumPoint
        WorldEdit.getInstance().newEditSession(weWorld).use { session ->
            val copy = ForwardExtentCopy(session, region, clipboard, region.minimumPoint)
            copy.isCopyingEntities = true
            copy.isCopyingBiomes = false
            Operations.complete(copy)
        }
        // 꾸밈 엔티티만 남긴다 — 짓는 곳에 돌아다니던 동물·떨어진 아이템까지 방마다 붙으면 안 된다.
        for (entity in clipboard.entities.toList()) {
            val type = entity.state?.type?.id()?.removePrefix("minecraft:")
            if (type !in DECORATIONS) clipboard.removeEntity(entity)
        }
        for (position in erase) clipboard.setBlock(position, BlockTypes.AIR!!.defaultState)
        return clipboard
    }

    private fun signsIn(world: World, min: Vec, max: Vec): List<Sign> {
        val out = ArrayList<Sign>()
        for (cx in (min.x shr 4)..(max.x shr 4)) for (cz in (min.z shr 4)..(max.z shr 4)) {
            if (!world.isChunkLoaded(cx, cz)) world.getChunkAt(cx, cz)
            for (state in world.getChunkAt(cx, cz).getTileEntities(false)) {
                if (state !is Sign) continue
                if (state.x in min.x..max.x && state.y in min.y..max.y && state.z in min.z..max.z) out.add(state)
            }
        }
        return out
    }

    private fun lines(sign: Sign, side: Side): List<String> =
        sign.getSide(side).lines().map { PlainTextComponentSerializer.plainText().serialize(it) }

    /** 표지판 글씨가 향하는 쪽 → 마크 시선(yaw). 남쪽 0, 서쪽 90, 북쪽 180, 동쪽 -90. */
    private fun yawOf(sign: Sign): Float {
        val face: BlockFace = when (val data = sign.blockData) {
            is Rotatable -> data.rotation
            is Directional -> data.facing
            else -> BlockFace.SOUTH
        }
        val direction = face.direction
        return Math.toDegrees(atan2(-direction.x, direction.z)).toFloat()
    }

    private companion object {
        val DECORATIONS = setOf(
            "item_frame", "glow_item_frame", "painting", "armor_stand", "leash_knot",
            "block_display", "item_display", "text_display",
        )
    }
}
