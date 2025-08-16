package com.yourdomain.survivalplus.modules

import com.yourdomain.survivalplus.SurvivalPlus
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import kotlin.random.Random

class VeinMinerModule(private val plugin: SurvivalPlus) : Module, Listener {

    private val processingBlocks = ThreadLocal.withInitial { mutableSetOf<Block>() }
    private val mineableOres = mutableSetOf<Material>()

    init {
        loadConfig()
    }

    fun loadConfig() {
        mineableOres.clear()
        val oreNames = plugin.config.getStringList("modules.vein-miner.mineable-ores")
        for (name in oreNames) {
            try {
                mineableOres.add(Material.valueOf(name.uppercase()))
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("[VeinMiner] Invalid material name in config: $name")
            }
        }
    }

    override fun getName(): String {
        return "vein-miner"
    }

    override fun getDescription(): String {
        return "Allows players to mine an entire vein of ore at once."
    }

    override fun enable() {
        // Register the listener
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    override fun disable() {
        // Unregister the listener
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

        if (!isVeinMineActive(player, originalBlock, item)) {
            return
        }

        val maxBlocks = plugin.config.getInt("modules.vein-miner.max-blocks", 64)
        val vein = findVein(originalBlock, maxBlocks)

        if (vein.size <= 1) {
            return
        }

        processingBlocks.get().addAll(vein)

        try {
            // The original block is broken by the player event, which also handles the first durability loss.
            // We just handle the rest of the vein.
            val remainingVein = vein - originalBlock

            for (veinBlock in remainingVein) {
                // Check if the tool has enough durability for one more block.
                if (!toolHasDurability(item)) {
                    break
                }

                // Fire a new BlockBreakEvent for each block to allow other plugins to interact
                // and to ensure player stats are counted.
                val newEvent = BlockBreakEvent(veinBlock, player)
                plugin.server.pluginManager.callEvent(newEvent)

                if (!newEvent.isCancelled) {
                    // Break the block, respecting enchantments.
                    veinBlock.breakNaturally(item)

                    // Apply damage for breaking the block.
                    damageTool(player, item)
                }
            }
        } finally {
            processingBlocks.get().clear()
        }
    }

    private fun isVeinMineActive(player: Player, block: Block, item: ItemStack): Boolean {
        // Player must not be sneaking
        if (player.isSneaking) return false
        // Player must be in survival mode
        if (player.gameMode != GameMode.SURVIVAL) return false
        // Must be using a pickaxe
        if (!Tag.ITEMS_PICKAXES.isTagged(item.type)) return false
        // Block must be a mineable ore
        if (block.type !in mineableOres) return false

        return true
    }

    private fun findVein(startBlock: Block, maxBlocks: Int): Set<Block> {
        val toVisit = ArrayDeque<Block>()
        val visited = mutableSetOf<Block>()
        val vein = mutableSetOf<Block>()
        val oreType = startBlock.type

        toVisit.add(startBlock)
        visited.add(startBlock)

        while (toVisit.isNotEmpty() && vein.size < maxBlocks) {
            val current = toVisit.removeFirst()
            vein.add(current)

            for (x in -1..1) {
                for (y in -1..1) {
                    for (z in -1..1) {
                        if (x == 0 && y == 0 && z == 0) continue
                        val neighbor = current.getRelative(x, y, z)
                        if (neighbor.type == oreType && visited.add(neighbor)) {
                            toVisit.add(neighbor)
                        }
                    }
                }
            }
        }
        return vein
    }

    private fun toolHasDurability(tool: ItemStack): Boolean {
        if (tool.type == Material.AIR) return false
        val meta = tool.itemMeta
        if (meta !is Damageable) return true // Not a damageable item

        return (tool.type.maxDurability - meta.damage) > 1
    }
    private fun damageTool(player: Player, tool: ItemStack) {
        if (tool.itemMeta !is Damageable) return

        val meta = tool.itemMeta as Damageable
        val unbreakingLevel = meta.getEnchantLevel(Enchantment.UNBREAKING)

        // Unbreaking formula: 1 / (level + 1) chance to not take damage
        if (unbreakingLevel > 0) {
            if (Random.nextInt(unbreakingLevel + 1) != 0) {
                return // No durability damage
            }
        }

        meta.damage += 1

        if (meta.damage >= tool.type.maxDurability) {
            // The tool broke
            player.inventory.setItemInMainHand(null)
            // You might want to play a sound or effect here
            player.playSound(player.location, org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f)
        } else {
            tool.itemMeta = meta
        }
    }
}
