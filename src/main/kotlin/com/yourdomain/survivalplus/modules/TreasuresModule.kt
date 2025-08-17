package com.yourdomain.survivalplus.modules

import com.yourdomain.survivalplus.SurvivalPlus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.GameMode
import org.bukkit.Material
import com.destroystokyo.paper.ParticleBuilder
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
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
        val config = plugin.configManager.getModuleConfig(getName().lowercase()) ?: return
        spawnChance = config.getDouble("global-settings.spawn-chance", 0.001)
        findCooldown = config.getLong("global-settings.find-cooldown", 60)

        val chancesSection = config.getConfigurationSection("rarity-chances")
        rarityChances = if (chancesSection != null) {
            chancesSection.getKeys(false).associateWith { chancesSection.getDouble(it) }
        } else {
            // Default values in case the config is missing this section
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

    fun isTreasure(block: Block): Boolean {
        return activeTreasures.containsKey(block)
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

        val config = plugin.configManager.getModuleConfig(getName().lowercase()) ?: return
        val lootConfigSection = config.getConfigurationSection("loot-tables") ?: return

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
            val plainString = (if (nbtIndex != -1) lootString.substring(0, nbtIndex) else lootString).trim()
            val nbtString = if (nbtIndex != -1) lootString.substring(nbtIndex) else null

            if (plainString.isBlank()) {
                return null
            }

            val parts = plainString.split(Regex("\\s+"))
            val materialName = parts.getOrNull(0)?.uppercase() ?: return null
            val material = Material.matchMaterial(materialName) ?: return null

            val quantityString = parts.getOrNull(1)
            val quantity = if (quantityString != null) {
                val quantityRange = quantityString.split("-")
                if (quantityRange.size == 2) {
                    try {
                        Random.nextInt(quantityRange[0].toInt(), quantityRange[1].toInt() + 1)
                    } catch (e: NumberFormatException) {
                        1
                    }
                } else {
                    quantityString.toIntOrNull() ?: 1
                }
            } else {
                1
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

        // Play a sound on spawn
        val (sound, pitch) = when (rarity.lowercase()) {
            "rare" -> Sound.ENTITY_PLAYER_LEVELUP to 0.7f
            "epic" -> Sound.ENTITY_PLAYER_LEVELUP to 0.9f
            "legendary" -> Sound.UI_TOAST_CHALLENGE_COMPLETE to 1.0f
            "mythic" -> Sound.ENTITY_ENDER_DRAGON_GROWL to 0.8f
            else -> Sound.ENTITY_ITEM_PICKUP to 1.0f
        }
        world.playSound(location, sound, 1.0f, pitch)

        return object : BukkitRunnable() {
            var tick = 0L
            override fun run() {
                if (!activeTreasures.containsKey(block)) {
                    this.cancel()
                    return
                }

                when (rarity.lowercase()) {
                    "rare" -> {
                        world.spawnParticle(Particle.END_ROD, location.clone().add(0.0, 0.5, 0.0), 2, 0.3, 0.3, 0.3, 0.0)
                    }
                    "epic" -> {
                        val angle1 = (tick * 12) * (Math.PI / 180)
                        val x1 = location.x + 0.8 * Math.cos(angle1)
                        val z1 = location.z + 0.8 * Math.sin(angle1)
                        val y1 = location.y + (tick % 25) * 0.05
                        Particle.DustOptions(Color.PURPLE, 1.0f).let {
                            world.spawnParticle(Particle.DUST, Location(world, x1, y1, z1), 1, it)
                        }

                        val angle2 = (tick * 12 + 180) * (Math.PI / 180)
                        val x2 = location.x + 0.8 * Math.cos(angle2)
                        val z2 = location.z + 0.8 * Math.sin(angle2)
                        val y2 = location.y + 1.25 - (tick % 25) * 0.05
                        Particle.DustOptions(Color.FUCHSIA, 1.0f).let {
                            world.spawnParticle(Particle.DUST, Location(world, x2, y2, z2), 1, it)
                        }
                    }
                    "legendary" -> {
                        world.spawnParticle(Particle.LAVA, location, 2, 0.2, 0.1, 0.2, 0.0)
                        if (tick % 5 == 0L) {
                            val x = location.x + (Random.nextDouble() - 0.5) * 1.5
                            val z = location.z + (Random.nextDouble() - 0.5) * 1.5
                            world.spawnParticle(Particle.FLAME, x, location.y, z, 5, 0.0, 0.5, 0.0, 0.05)
                        }
                    }
                    "mythic" -> {
                        val angle = tick * 8 * (Math.PI / 180)
                        for (i in 0..2) {
                            val x = location.x + 1.5 * Math.cos(angle + i * (2 * Math.PI / 3))
                            val z = location.z + 1.5 * Math.sin(angle + i * (2 * Math.PI / 3))
                            world.spawnParticle(Particle.PORTAL, x, location.y + 0.5, z, 1, 0.0, 0.0, 0.0, 0.0)
                        }
                        if (tick % 20 == 0L) {
                            world.spawnParticle(Particle.FIREWORK, location, 1, 0.2, 0.2, 0.2, 0.1)
                            world.playSound(location, Sound.BLOCK_BEACON_AMBIENT, 0.5f, 1.5f)
                        }
                    }
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }
}
