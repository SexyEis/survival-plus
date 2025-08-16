package com.yourdomain.survivalplus.modules

import com.yourdomain.survivalplus.SurvivalPlus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.*
import kotlin.random.Random

class TreasuresModule(private val plugin: SurvivalPlus) : Module, Listener {

    private data class Treasure(val hologram: ArmorStand, val particleTask: BukkitTask)
    private val activeTreasures = mutableMapOf<Block, Treasure>()

    private val playerCooldowns = mutableMapOf<UUID, Long>()
    private var spawnChance = 0.001
    private var findCooldown = 60L // in seconds
    private lateinit var rarityChances: Map<String, Double>

    init {
        loadConfig()
    }

    private fun loadConfig() {
        val config = plugin.configManager.getConfig()
        spawnChance = config.getDouble("modules.treasures.global-settings.spawn-chance", 0.001)
        findCooldown = config.getLong("modules.treasures.global-settings.find-cooldown", 60)

        val chancesSection = config.getConfigurationSection("modules.treasures.rarity-chances")
        rarityChances = if (chancesSection != null) {
            chancesSection.getKeys(false).associateWith { chancesSection.getDouble(it) }
        } else {
            mapOf("normal" to 0.50, "rare" to 0.25, "epic" to 0.15, "legendary" to 0.08, "mythic" to 0.02)
        }
    }

    override fun getName(): String = "treasures"

    override fun getDescription(): String = "Spawns treasure chests with biome-specific loot while mining."

    override fun enable() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        loadConfig()
    }

    override fun disable() {
        org.bukkit.event.HandlerList.unregisterAll(this)
        activeTreasures.keys.toList().forEach { removeTreasure(it) }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block

        if (block.type == Material.CHEST && activeTreasures.containsKey(block)) {
            removeTreasure(block, true)
            event.isDropItems = false
            return
        }

        if (player.gameMode != GameMode.SURVIVAL) {
            return
        }

        val lastFound = playerCooldowns[player.uniqueId]
        if (lastFound != null && (System.currentTimeMillis() - lastFound) < findCooldown * 1000) {
            return
        }

        if (Random.nextDouble() > spawnChance) {
            return
        }

        val rarity = getRandomRarity() ?: return
        spawnTreasureChest(player, block, rarity)

        playerCooldowns[player.uniqueId] = System.currentTimeMillis()
    }

    private fun removeTreasure(block: Block, broken: Boolean = false) {
        activeTreasures.remove(block)?.let {
            it.hologram.remove()
            it.particleTask.cancel()
            if (!broken) {
                block.type = Material.AIR
            }
        }
    }

    private fun getRandomRarity(): String? {
        val totalWeight = rarityChances.values.sum()
        if (totalWeight <= 0) return null

        var random = Random.nextDouble() * totalWeight
        for ((rarity, weight) in rarityChances) {
            if (random < weight) {
                return rarity
            }
            random -= weight
        }
        return rarityChances.keys.lastOrNull()
    }

    private fun spawnTreasureChest(player: Player, block: Block, rarity: String) {
        val originalType = block.type
        block.type = Material.CHEST

        val hologram = spawnHologram(block, rarity)
        val particleTask = startParticleEffect(block, rarity)

        if (hologram != null) {
            activeTreasures[block] = Treasure(hologram, particleTask)
            populateChest(block, rarity)

            object : BukkitRunnable() {
                override fun run() {
                    if (activeTreasures.containsKey(block)) {
                       removeTreasure(block)
                    }
                }
            }.runTaskLater(plugin, 20L * 60 * 5) // 5 minutes

        } else {
            block.type = originalType
            particleTask.cancel()
        }

        plugin.logger.info("A '$rarity' treasure chest spawned for ${player.name}!")
    }

    private fun populateChest(block: Block, rarity: String) {
        val chest = block.state as? Chest ?: return
        val inventory = chest.inventory
        val biome = block.biome.key().key()

        val lootConfigSection = plugin.configManager.getConfig().getConfigurationSection("modules.treasures.loot-tables") ?: return

        val lootList = lootConfigSection.getStringList("$biome.$rarity")
            .ifEmpty { lootConfigSection.getStringList("default.$rarity") }

        if (lootList.isEmpty()) {
            plugin.logger.warning("[Treasures] No loot table found for rarity '$rarity' in biome '$biome' or in default.")
            return
        }

        val itemCount = when (rarity.lowercase()) {
            "normal" -> Random.nextInt(2, 4)
            "rare" -> Random.nextInt(3, 6)
            "epic" -> Random.nextInt(4, 7)
            "legendary" -> Random.nextInt(5, 8)
            "mythic" -> Random.nextInt(6, 9)
            else -> 0
        }

        val possibleItems = lootList.mapNotNull { parseLootString(it) }
        if (possibleItems.isEmpty()) return

        val availableSlots = (0 until inventory.size).toMutableList()
        repeat(itemCount) {
            if (availableSlots.isEmpty() || possibleItems.isEmpty()) return

            val item = possibleItems.random()
            val slot = availableSlots.random()
            availableSlots.remove(slot)

            inventory.setItem(slot, item)
        }
    }

    @Suppress("DEPRECATION")
    private fun parseLootString(lootString: String): ItemStack? {
        try {
            val nbtIndex = lootString.indexOf('{')
            val plainString = if (nbtIndex != -1) lootString.substring(0, nbtIndex).trim() else lootString
            val nbtString = if (nbtIndex != -1) lootString.substring(nbtIndex) else null

            val parts = plainString.split(" ")
            val materialName = parts.getOrNull(0)?.uppercase() ?: return null
            val material = Material.matchMaterial(materialName) ?: return null

            val quantityRange = parts.getOrNull(1)?.split("-")
            val quantity = if (quantityRange != null && quantityRange.size == 2) {
                Random.nextInt(quantityRange[0].toInt(), quantityRange[1].toInt() + 1)
            } else {
                parts.getOrNull(1)?.toIntOrNull() ?: 1
            }

            val item = ItemStack(material, quantity)

            if (nbtString != null) {
                plugin.server.getUnsafe().modifyItemStack(item, nbtString)
            }

            return item
        } catch (e: Exception) {
            plugin.logger.warning("[Treasures] Failed to parse loot string: '$lootString'. Error: ${e.message}")
            return null
        }
    }

    private fun spawnHologram(block: Block, rarity: String): ArmorStand? {
        val location = block.location.add(0.5, 0.5, 0.5)
        val world = location.world ?: return null

        val (text, color) = when (rarity.lowercase()) {
            "normal" -> "Normal Treasure" to NamedTextColor.WHITE
            "rare" -> "Rare Treasure" to NamedTextColor.BLUE
            "epic" -> "Epic Treasure" to NamedTextColor.DARK_PURPLE
            "legendary" -> "Legendary Treasure" to NamedTextColor.GOLD
            "mythic" -> "Mythic Treasure" to NamedTextColor.LIGHT_PURPLE
            else -> "Treasure" to NamedTextColor.GRAY
        }

        return world.spawn(location, ArmorStand::class.java) {
            it.isMarker = true
            it.isVisible = false
            it.setGravity(false)
            it.isSmall = true
            it.customName(Component.text(text).color(color).decorate(TextDecoration.BOLD))
            it.isCustomNameVisible = true
        }
    }

    private fun startParticleEffect(block: Block, rarity: String): BukkitTask {
        val location = block.location.add(0.5, 0.5, 0.5)
        val world = location.world!!

        return object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (!activeTreasures.containsKey(block)) {
                    this.cancel()
                    return
                }

                when (rarity.lowercase()) {
                    "rare" -> {
                        val angle = (tick * 10) * (Math.PI / 180)
                        val x = location.x + 0.7 * Math.cos(angle)
                        val z = location.z + 0.7 * Math.sin(angle)
                        world.spawnParticle(Particle.CRIT, x, location.y, z, 1, 0.0, 0.0, 0.0, 0.0)
                    }
                    "epic" -> {
                        for (i in 0..2) {
                            val angle = (tick * 5 + i * 120) * (Math.PI / 180)
                            val x = location.x + 1.0 * Math.cos(angle)
                            val z = location.z + 1.0 * Math.sin(angle)
                            world.spawnParticle(Particle.ENCHANT, x, location.y, z, 1, 0.0, 0.0, 0.0, 0.0)
                        }
                    }
                    "legendary" -> {
                        world.spawnParticle(Particle.FLAME, location, 3, 0.4, 0.4, 0.4, 0.01)
                    }
                    "mythic" -> {
                        val angle = tick * 18 * (Math.PI / 180)
                        val x = location.x + 1.2 * Math.cos(angle)
                        val z = location.z + 1.2 * Math.sin(angle)
                        world.spawnParticle(Particle.DRAGON_BREATH, x, location.y, z, 1, 0.0, 0.0, 0.0, 0.0)
                        world.spawnParticle(Particle.PORTAL, location, 5, 0.5, 0.5, 0.5, 0.2)
                    }
                    else -> {
                        // No particles for normal rarity
                    }
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }
}
