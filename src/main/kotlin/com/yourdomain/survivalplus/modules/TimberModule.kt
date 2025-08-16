package com.yourdomain.survivalplus.modules

import com.yourdomain.survivalplus.SurvivalPlus
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.data.type.Leaves
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.scheduler.BukkitRunnable
import kotlin.random.Random

class TimberModule(private val plugin: SurvivalPlus) : Module, Listener {

    private val processingBlocks = ThreadLocal.withInitial { mutableSetOf<Block>() }
    private val logTypes = mutableSetOf<Material>()
    private val leafTypes = mutableSetOf<Material>()

    init {
        loadConfig()
    }

    fun loadConfig() {
        logTypes.clear()
        plugin.config.getStringList("modules.timber.log-types").forEach {
            try {
                logTypes.add(Material.valueOf(it.uppercase()))
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("[Timber] Invalid log material name in config: $it")
            }
        }

        leafTypes.clear()
        plugin.config.getStringList("modules.timber.leaf-types").forEach {
            try {
                leafTypes.add(Material.valueOf(it.uppercase()))
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("[Timber] Invalid leaf material name in config: $it")
            }
        }
    }

    override fun getName(): String {
        return "timber"
    }

    override fun getDescription(): String {
        return "Chop down entire trees at once and causes leaves to decay quickly."
    }

    override fun enable() {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    override fun disable() {
        org.bukkit.event.HandlerList.unregisterAll(this)
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (processingBlocks.get().contains(event.block)) {
            return
        }

        val player = event.player
        val originalBlock = event.block
        val item = player.inventory.itemInMainHand

        if (!isTimberActive(player, originalBlock, item)) {
            return
        }

        val (logs, leaves) = findTree(originalBlock)

        if (logs.size <= 1) {
            return
        }

        processingBlocks.get().addAll(logs)
        try {
            val remainingLogs = logs - originalBlock
            for (logBlock in remainingLogs) {
                if (!toolHasDurability(item)) break

                val breakEvent = BlockBreakEvent(logBlock, player)
                plugin.server.pluginManager.callEvent(breakEvent)
                if (!breakEvent.isCancelled) {
                    logBlock.breakNaturally(item)
                    damageTool(player, item)
                }
            }
            decayLeaves(leaves)
        } finally {
            processingBlocks.get().clear()
        }
    }

    private fun isTimberActive(player: Player, block: Block, item: ItemStack): Boolean {
        if (player.isSneaking || player.gameMode != GameMode.SURVIVAL) return false
        if (item.type !in Tag.ITEMS_AXES.values) return false
        if (block.type !in logTypes) return false
        return isNaturalTree(block)
    }

    private fun isNaturalTree(block: Block): Boolean {
        // A simple heuristic: check for non-persistent leaves in a 5x5 area around the log.
        for (x in -2..2) {
            for (y in 0..4) {
                for (z in -2..2) {
                    val relative = block.getRelative(x, y, z)
                    if (relative.type in leafTypes) {
                        if (relative.blockData is Leaves) {
                            val leafData = relative.blockData as Leaves
                            if (!leafData.isPersistent) {
                                return true // Found at least one natural leaf block, assume it's a tree
                            }
                        }
                    }
                }
            }
        }
        return false
    }

    private fun findTree(startBlock: Block): Pair<Set<Block>, Set<Block>> {
        val maxLogs = plugin.config.getInt("modules.timber.max-logs", 256)
        val logs = mutableSetOf<Block>()
        val leaves = mutableSetOf<Block>()

        val toVisit = ArrayDeque<Block>()
        val visited = mutableSetOf<Block>()

        toVisit.add(startBlock)
        visited.add(startBlock)

        // Find all connected logs
        while (toVisit.isNotEmpty() && logs.size < maxLogs) {
            val current = toVisit.removeFirst()
            if (current.type in logTypes) {
                logs.add(current)
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

        // Find all leaves within a configured radius of any log
        val leafSearchRadius = plugin.config.getInt("modules.timber.leaf-search-radius", 6)
        for (log in logs) {
            for (x in -leafSearchRadius..leafSearchRadius) {
                for (y in -leafSearchRadius..leafSearchRadius) {
                    for (z in -leafSearchRadius..leafSearchRadius) {
                        val block = log.getRelative(x, y, z)
                        if (block.type in leafTypes) {
                            leaves.add(block)
                        }
                    }
                }
            }
        }

        return Pair(logs, leaves)
    }

    private fun decayLeaves(leavesToDecay: Set<Block>) {
        if (leavesToDecay.isEmpty()) return

        val leavesIterator = leavesToDecay.iterator()

        object : BukkitRunnable() {
            override fun run() {
                for (i in 1..100) { // Check up to 100 leaves per tick
                    if (!leavesIterator.hasNext()) {
                        this.cancel()
                        return
                    }
                    val leaf = leavesIterator.next()
                    if (leaf.type in leafTypes && (leaf.blockData as? Leaves)?.isPersistent == false) {
                        if (!isLeafConnectedToLog(leaf)) {
                            val decayEvent = LeavesDecayEvent(leaf)
                            plugin.server.pluginManager.callEvent(decayEvent)
                            if (!decayEvent.isCancelled) {
                                leaf.breakNaturally()
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 2L) // Start after 1 sec, run every 2 ticks
    }

    private fun isLeafConnectedToLog(leaf: Block): Boolean {
        val toVisit = ArrayDeque<Block>()
        val visited = mutableSetOf<Block>()
        toVisit.add(leaf)
        visited.add(leaf)

        var distance = 0
        while (toVisit.isNotEmpty() && distance < 7) {
            val size = toVisit.size
            repeat(size) {
                val current = toVisit.removeFirst()
                if (current.type in logTypes) {
                    return true // Found a log
                }

                for (x in -1..1) {
                    for (y in -1..1) {
                        for (z in -1..1) {
                            if (x == 0 && y == 0 && z == 0) continue
                            val neighbor = current.getRelative(x, y, z)
                            if (neighbor.type in leafTypes || neighbor.type in logTypes) {
                                if (visited.add(neighbor)) {
                                    toVisit.add(neighbor)
                                }
                            }
                        }
                    }
                }
            }
            distance++
        }
        return false
    }

    private fun toolHasDurability(tool: ItemStack): Boolean {
        if (tool.type == Material.AIR) return false
        val meta = tool.itemMeta
        if (meta !is Damageable) return true
        return (tool.type.maxDurability - meta.damage) > 1
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
