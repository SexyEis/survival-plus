package com.yourdomain.survivalplus.modules

import com.yourdomain.survivalplus.SurvivalPlus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import java.util.*
import kotlin.random.Random

class UltimineModule(private val plugin: SurvivalPlus) : Module, Listener {

    enum class Mode {
        NORMAL, TUNNEL, BIG_TUNNEL, MAX_BREAK
    }

    private val playerModes = mutableMapOf<UUID, Mode>()
    private val ultimineActive = mutableMapOf<UUID, Boolean>()
    private val processingBlocks = ThreadLocal.withInitial { mutableSetOf<Block>() }
    private lateinit var gui: UltimineGUI

    override fun getName(): String = "ultimine"
    override fun getDescription(): String = "Adds ultimate mining capabilities with different modes."

    override fun enable() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        gui = UltimineGUI(plugin, this)
    }

    override fun disable() {
        org.bukkit.event.HandlerList.unregisterAll(this)
    }

    fun getPlayerMode(player: Player): Mode = playerModes.getOrPut(player.uniqueId) { Mode.NORMAL }
    fun setPlayerMode(player: Player, mode: Mode) {
        playerModes[player.uniqueId] = mode
    }

    fun isUltimineActive(player: Player): Boolean = ultimineActive.getOrPut(player.uniqueId) { false }
    fun toggleUltimineActive(player: Player): Boolean {
        val newState = !isUltimineActive(player)
        ultimineActive[player.uniqueId] = newState
        return newState
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (player.isSneaking && (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK)) {
            if (player.inventory.itemInMainHand.type.isEdible || player.inventory.itemInMainHand.type == Material.SHIELD) return
            event.isCancelled = true
            gui.open(player)
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (processingBlocks.get().contains(event.block)) return

        val player = event.player
        val originalBlock = event.block
        val item = player.inventory.itemInMainHand

        if (!isUltimineActive(player) || player.gameMode != GameMode.SURVIVAL) return

        val blocksToBreak = when (getPlayerMode(player)) {
            Mode.NORMAL -> findAdjacentBlocks(originalBlock)
            Mode.TUNNEL -> findTunnelBlocks(originalBlock, player.facing, 2, 1, item)
            Mode.BIG_TUNNEL -> findTunnelBlocks(originalBlock, player.facing, 3, 3, item)
            Mode.MAX_BREAK -> {
                if (Tag.ITEMS_AXES.isTagged(item.type) && Tag.LOGS.isTagged(originalBlock.type)) {
                    findTreeLikeBlocks(player, originalBlock, item)
                } else {
                    findMaxBreakBlocks(player, originalBlock, item)
                }
            }
        }

        if (blocksToBreak.isEmpty()) return

        event.isCancelled = true // We handle the breaking ourselves to collect drops
        processingBlocks.get().addAll(blocksToBreak)
        try {
            breakBlocksAndCollect(player, blocksToBreak, item)
        } finally {
            processingBlocks.get().clear()
        }
    }

    private fun breakBlocksAndCollect(player: Player, blocks: Set<Block>, tool: ItemStack) {
        val allDrops = mutableListOf<ItemStack>()
        val treasuresModule = plugin.moduleManager.getModule("treasures") as? TreasuresModule
        for (block in blocks) {
            if (treasuresModule != null && treasuresModule.isTreasure(block)) {
                continue
            }
            if (!toolHasDurability(tool)) break

            val breakEvent = BlockBreakEvent(block, player)
            plugin.server.pluginManager.callEvent(breakEvent)

            if (!breakEvent.isCancelled) {
                allDrops.addAll(block.getDrops(tool))
                block.type = Material.AIR
                damageTool(player, tool)
            }
        }

        val condensedDrops = allDrops.groupBy { it.type }.map { (material, items) ->
            val totalAmount = items.sumOf { it.amount }
            ItemStack(material, totalAmount)
        }

        val toolStorageModule = plugin.moduleManager.getModule("tool-storage") as? ToolStorageModule
        if (toolStorageModule != null && plugin.moduleManager.isModuleEnabled("tool-storage")) {
            for (itemStack in condensedDrops) {
                toolStorageModule.addToToolInventory(tool, itemStack)
            }
        } else {
            for (itemStack in condensedDrops) {
                val leftover = player.inventory.addItem(itemStack)
                for (item in leftover.values) {
                    player.world.dropItemNaturally(player.location, item)
                }
            }
        }
    }

    private fun findAdjacentBlocks(startBlock: Block): Set<Block> {
        val maxBlocks = plugin.config.getInt("modules.ultimine.max-blocks", 64)
        val toVisit = ArrayDeque<Block>()
        val visited = mutableSetOf<Block>()
        val blocks = mutableSetOf<Block>()
        val blockType = startBlock.type

        toVisit.add(startBlock)
        visited.add(startBlock)

        while (toVisit.isNotEmpty() && blocks.size < maxBlocks) {
            val current = toVisit.removeFirst()
            blocks.add(current)

            for (x in -1..1) {
                for (y in -1..1) {
                    for (z in -1..1) {
                        if (x == 0 && y == 0 && z == 0) continue
                        val neighbor = current.getRelative(x, y, z)
                        if (neighbor.type == blockType && visited.add(neighbor)) {
                            toVisit.add(neighbor)
                        }
                    }
                }
            }
        }
        return blocks
    }

    private fun findTreeLikeBlocks(player: Player, startBlock: Block, tool: ItemStack): Set<Block> {
        val maxBlocks = plugin.config.getInt("modules.ultimine.max-blocks", 64)
        val blocks = mutableSetOf<Block>()
        val toVisit = ArrayDeque<Block>()
        val visited = mutableSetOf<Block>()
        toVisit.add(startBlock)
        visited.add(startBlock)

        while (toVisit.isNotEmpty() && blocks.size < maxBlocks) {
            val current = toVisit.removeFirst()
            if (Tag.LOGS.isTagged(current.type)) {
                blocks.add(current)

                for (x in -1..1) {
                    for (y in -1..1) {
                        for (z in -1..1) {
                            if (x == 0 && y == 0 && z == 0) continue
                            val neighbor = current.getRelative(x, y, z)
                            if (visited.add(neighbor)) {
                                toVisit.add(neighbor)
                            }
                        }
                    }
                }
            }
        }
        return blocks
    }

    private fun findTunnelBlocks(startBlock: Block, direction: BlockFace, width: Int, height: Int, tool: ItemStack): Set<Block> {
        val maxBlocks = plugin.config.getInt("modules.ultimine.max-blocks", 64)
        val blocks = mutableSetOf<Block>()
        val length = maxBlocks / (width * height)

        val (right, up) = when (direction) {
            BlockFace.NORTH, BlockFace.SOUTH -> BlockFace.EAST to BlockFace.UP
            BlockFace.EAST, BlockFace.WEST -> BlockFace.SOUTH to BlockFace.UP
            BlockFace.UP, BlockFace.DOWN -> BlockFace.EAST to (if(direction == BlockFace.UP) BlockFace.NORTH else BlockFace.SOUTH)
            else -> return emptySet()
        }

        val startCorner = startBlock.getRelative(right, -(width / 2)).getRelative(up, -(height/2))

        for (l in 0 until length) {
            for (h in 0 until height) {
                for (w in 0 until width) {
                    val block = startCorner.getRelative(direction, l).getRelative(up, h).getRelative(right, w)
                    if (canToolBreak(tool, block)) {
                        blocks.add(block)
                    }
                }
            }
        }

        return blocks
    }

    private fun findMaxBreakBlocks(player: Player, startBlock: Block, tool: ItemStack): Set<Block> {
        val maxBlocks = plugin.config.getInt("modules.ultimine.max-blocks", 64)
        val blocks = mutableSetOf<Block>()
        val toVisit = ArrayDeque<Block>()
        val visited = mutableSetOf<Block>()
        toVisit.add(startBlock)
        visited.add(startBlock)

        while (toVisit.isNotEmpty() && blocks.size < maxBlocks) {
            val current = toVisit.removeFirst()
            if (canToolBreak(tool, current)) {
                blocks.add(current)

                for (x in -1..1) {
                    for (y in -1..1) {
                        for (z in -1..1) {
                            if (x == 0 && y == 0 && z == 0) continue
                            val neighbor = current.getRelative(x, y, z)
                            if (visited.add(neighbor)) {
                                toVisit.add(neighbor)
                            }
                        }
                    }
                }
            }
        }
        return blocks
    }

    private fun canToolBreak(tool: ItemStack, block: Block): Boolean {
        // This is a simplified check. A more robust implementation would check tool types vs block materials.
        return when {
            Tag.ITEMS_PICKAXES.isTagged(tool.type) -> Tag.MINEABLE_PICKAXE.isTagged(block.type)
            Tag.ITEMS_AXES.isTagged(tool.type) -> Tag.MINEABLE_AXE.isTagged(block.type)
            Tag.ITEMS_SHOVELS.isTagged(tool.type) -> Tag.MINEABLE_SHOVEL.isTagged(block.type)
            Tag.ITEMS_HOES.isTagged(tool.type) -> Tag.MINEABLE_HOE.isTagged(block.type)
            else -> false
        }
    }

    private fun toolHasDurability(tool: ItemStack): Boolean {
        if (tool.type == Material.AIR) return false
        val meta = tool.itemMeta
        if (meta !is Damageable) return true
        return meta.damage < tool.type.maxDurability
    }

    private fun damageTool(player: Player, tool: ItemStack) {
        if (tool.itemMeta !is Damageable) return

        val meta = tool.itemMeta as Damageable
        val unbreakingLevel = meta.getEnchantLevel(Enchantment.UNBREAKING)

        if (unbreakingLevel > 0 && Random.nextInt(unbreakingLevel + 1) != 0) {
            return // No durability damage
        }

        meta.damage += 1

        if (meta.damage >= tool.type.maxDurability) {
            player.inventory.setItemInMainHand(null)
            player.playSound(player.location, org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f)
        } else {
            tool.itemMeta = meta
        }
    }
}
