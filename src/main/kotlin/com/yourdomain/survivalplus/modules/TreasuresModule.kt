package com.yourdomain.survivalplus.modules

import com.yourdomain.survivalplus.SurvivalPlus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
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

    fun loadConfig() {
        val config = plugin.configManager.getModuleConfig(getName().lowercase()) ?: return
        spawnChance = config.getDouble("global-settings.spawn-chance", 0.001)
        findCooldown = config.getLong("global-settings.find-cooldown", 60)

        val chancesSection = config.getConfigurationSection("rarity-chances")
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

    fun isTreasure(block: Block): Boolean {
        return activeTreasures.containsKey(block)
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block

        if (block.type == Material.CHEST && activeTreasures.containsKey(block)) {
            val chest = block.state as? Chest
            if (chest != null) {
                chest.inventory.contents.forEach { item ->
                    if (item != null) {
                        block.world.dropItemNaturally(block.location, item)
                    }
                }
                chest.inventory.clear()
            }
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
        val blockLocation = block.location.clone()
        object : BukkitRunnable() {
            override fun run() {
                spawnTreasureChest(player, blockLocation.block, rarity)
            }
        }.runTaskLater(plugin, 1L)

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
        if (originalType.isAir) return
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

    private data class PotionEffectRule(val type: PotionEffectType, val amplifier: Int, val duration: Int)
    private data class LootRule(
        val itemString: String,
        val biomes: List<String>,
        val rarities: List<String>,
        val amountRange: IntRange,
        val chance: Double,
        val nbt: String?,
        val potionEffects: List<PotionEffectRule>
    )

    private fun parseLootRule(rawString: String): LootRule? {
        try {
            val parts = rawString.split("#").map { it.trim() }
            val itemPart = parts[0]
            val tags = parts.drop(1)

            val nbtIndex = itemPart.indexOf('{')
            val plainItemString = (if (nbtIndex != -1) itemPart.substring(0, nbtIndex) else itemPart).trim()
            val nbtString = if (nbtIndex != -1) itemPart.substring(nbtIndex) else null

            var biomes = listOf("all")
            var rarities = listOf("all")
            var amountRange = 1..1
            var chance = 1.0
            var potionEffects = emptyList<PotionEffectRule>()

            tags.forEach { tag ->
                val tagParts = tag.split(":", limit = 2)
                if (tagParts.size == 2) {
                    val key = tagParts[0].lowercase()
                    val value = tagParts[1]
                    when (key) {
                        "b" -> biomes = value.split(",").map { it.trim() }
                        "r" -> rarities = value.split(",").map { it.trim() }
                        "a" -> {
                            val amountParts = value.split("-")
                            amountRange = if (amountParts.size == 2) {
                                amountParts[0].toInt()..amountParts[1].toInt()
                            } else {
                                val amount = value.toInt()
                                amount..amount
                            }
                        }
                        "c" -> chance = value.toDouble()
                        "pe" -> {
                            potionEffects = value.split(",").mapNotNull { effectString ->
                                val effectParts = effectString.trim().split("_")
                                if (effectParts.size == 3) {
                                    val type = PotionEffectType.getByName(effectParts[0].uppercase()) ?: return@mapNotNull null
                                    val amplifier = effectParts[1].toInt()
                                    val duration = effectParts[2].toInt() * 20 // Convert seconds to ticks
                                    PotionEffectRule(type, amplifier, duration)
                                } else null
                            }
                        }
                    }
                }
            }
            return LootRule(plainItemString, biomes, rarities, amountRange, chance, nbtString, potionEffects)
        } catch (e: Exception) {
            plugin.logger.warning("[Treasures] Failed to parse loot rule: '$rawString'. Error: ${e.message}")
            return null
        }
    }

    private fun populateChest(block: Block, rarity: String) {
        val chest = block.state as? Chest ?: return
        val inventory = chest.inventory
        val biome = block.biome.key.key

        val config = plugin.configManager.getModuleConfig(getName().lowercase()) ?: return
        val allLootRules = config.getStringList("loot-pool").mapNotNull { parseLootRule(it) }

        val applicableRules = allLootRules.filter { rule ->
            (rule.rarities.contains("all") || rule.rarities.contains(rarity)) &&
                    (rule.biomes.contains("all") || rule.biomes.contains(biome))
        }

        if (applicableRules.isEmpty()) {
            plugin.logger.warning("[Treasures] No applicable loot rules found for rarity '$rarity' in biome '$biome'.")
            return
        }

        val itemsToGenerate = applicableRules.filter { Random.nextDouble() < it.chance }

        val itemCount = when (rarity.lowercase()) {
            "normal" -> Random.nextInt(2, 4)
            "rare" -> Random.nextInt(3, 6)
            "epic" -> Random.nextInt(4, 7)
            "legendary" -> Random.nextInt(5, 8)
            "mythic" -> Random.nextInt(6, 9)
            else -> 0
        }

        val availableSlots = (0 until inventory.size).toMutableList()
        repeat(itemCount.coerceAtMost(27)) {
            if (availableSlots.isEmpty() || itemsToGenerate.isEmpty()) return

            val rule = itemsToGenerate.random()
            val material = Material.matchMaterial(rule.itemString) ?: return@repeat
            val amount = Random.nextInt(rule.amountRange.first, rule.amountRange.last + 1)
            val item = ItemStack(material, amount)

            if (item.itemMeta is PotionMeta && rule.potionEffects.isNotEmpty()) {
                val potionMeta = item.itemMeta as PotionMeta
                rule.potionEffects.forEach { effectRule ->
                    potionMeta.addCustomEffect(PotionEffect(effectRule.type, effectRule.duration, effectRule.amplifier), true)
                }
                item.itemMeta = potionMeta
            }

            if (rule.nbt != null) {
                try {
                    @Suppress("DEPRECATION")
                    plugin.server.getUnsafe().modifyItemStack(item, rule.nbt)
                } catch (e: Exception) {
                    plugin.logger.warning("Failed to apply NBT to item ${material.name}: ${e.message}")
                }
            }

            val slot = availableSlots.random()
            availableSlots.remove(slot)
            inventory.setItem(slot, item)
        }
    }

    private fun spawnHologram(block: Block, rarity: String): ArmorStand? {
        val location = block.location.add(0.5, 1.0, 0.5)
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

        val (sound, pitch) = when (rarity.lowercase()) {
            "rare" -> Sound.ENTITY_PLAYER_LEVELUP to 0.7f
            "epic" -> Sound.ENTITY_PLAYER_LEVELUP to 0.9f
            "legendary" -> Sound.UI_TOAST_CHALLENGE_COMPLETE to 1.0f
            "mythic" -> Sound.ENTITY_ENDER_DRAGON_GROWL to 0.8f
            else -> Sound.ENTITY_ITEM_PICKUP to 1.0f
        }
        world.playSound(location, sound, 1.0f, pitch)

        val animationType = Random.nextInt(3)

        return object : BukkitRunnable() {
            var tick = 0L
            override fun run() {
                if (!activeTreasures.containsKey(block)) {
                    this.cancel()
                    return
                }

                val angle_fast = tick * 5 * (Math.PI / 180)
                val angle_slow = tick * 0.5 * (Math.PI / 180)

                when (rarity.lowercase()) {
                    "normal" -> {
                        when(animationType) {
                            0 -> { // Simple Sparkle
                                if (tick % 10 == 0L) {
                                    world.spawnParticle(Particle.HAPPY_VILLAGER, location, 5, 0.5, 0.5, 0.5, 0.0)
                                }
                            }
                            1 -> { // Gentle Swirl
                                val x = location.x + 0.8 * Math.cos(angle_slow * 5)
                                val z = location.z + 0.8 * Math.sin(angle_slow * 5)
                                world.spawnParticle(Particle.CRIT, x, location.y + 0.5, z, 1, 0.0, 0.0, 0.0, 0.0)
                            }
                            2 -> { // Popping bubbles
                                if (tick % 15 == 0L) {
                                    world.spawnParticle(Particle.BUBBLE_POP, location, 3, 0.4, 0.4, 0.4, 0.1)
                                }
                            }
                        }
                    }
                    "rare" -> {
                        when(animationType) {
                            0 -> { // Blue Orbit
                                val x = location.x + 1.2 * Math.cos(angle_fast)
                                val z = location.z + 1.2 * Math.sin(angle_fast)
                                Particle.DustOptions(Color.BLUE, 1.2f).let {
                                    world.spawnParticle(Particle.DUST, x, location.y + 0.8, z, 1, it)
                                }
                            }
                            1 -> { // End Rod Pulses
                                if (tick % 20 == 0L) {
                                    world.spawnParticle(Particle.END_ROD, location, 10, 0.1, 0.5, 0.1, 0.05)
                                }
                            }
                            2 -> { // Rising Notes
                                if (tick % 8 == 0L) {
                                    val x = location.x + (Random.nextDouble() - 0.5) * 1.5
                                    val z = location.z + (Random.nextDouble() - 0.5) * 1.5
                                    world.spawnParticle(Particle.NOTE, x, location.y + 1, z, 1)
                                }
                            }
                        }
                    }
                    "epic" -> {
                        when(animationType) {
                            0 -> { // Purple Helix
                                val x1 = location.x + 1.0 * Math.cos(angle_fast * 2)
                                val z1 = location.z + 1.0 * Math.sin(angle_fast * 2)
                                val y1 = location.y + (tick % 40) * 0.05
                                Particle.DustOptions(Color.PURPLE, 1.5f).let {
                                    world.spawnParticle(Particle.DUST, Location(world, x1, y1, z1), 1, it)
                                }
                                val x2 = location.x + 1.0 * Math.cos(angle_fast * 2 + Math.PI)
                                val z2 = location.z + 1.0 * Math.sin(angle_fast * 2 + Math.PI)
                                Particle.DustOptions(Color.FUCHSIA, 1.5f).let {
                                    world.spawnParticle(Particle.DUST, Location(world, x2, y1, z2), 1, it)
                                }
                            }
                            1 -> { // Enchanting Glyphs
                                val x = location.x + (Random.nextDouble() - 0.5) * 2.0
                                val y = location.y + (Random.nextDouble() - 0.5) * 2.0
                                val z = location.z + (Random.nextDouble() - 0.5) * 2.0
                                if (tick % 4 == 0L) {
                                    world.spawnParticle(Particle.ENCHANT, Location(world, x, y, z), 1)
                                }
                            }
                            2 -> { // Witch Sparks
                                val x = location.x + Math.cos(angle_fast * 3) * (1.5 - (tick % 50) / 50.0)
                                val z = location.z + Math.sin(angle_fast * 3) * (1.5 - (tick % 50) / 50.0)
                                val y = location.y + (tick % 50) * 0.04
                                world.spawnParticle(Particle.WITCH, x, y, z, 1, 0.0, 0.0, 0.0, 0.0)
                            }
                        }
                    }
                    "legendary" -> {
                        when(animationType) {
                            0 -> { // Golden Beacon
                                for(i in 0..4) {
                                    val y = location.y + i*0.5 + (tick % 10)*0.05
                                    world.spawnParticle(Particle.FLAME, location.x, y, location.z, 2, 0.1, 0.1, 0.1, 0.0)
                                }
                                Particle.DustOptions(Color.ORANGE, 2.0f).let {
                                    world.spawnParticle(Particle.DUST, location, 10, 0.5, 0.5, 0.5, it)
                                }
                            }
                            1 -> { // Lava Drips with Smoke
                                if (tick % 10 == 0L) {
                                    world.spawnParticle(Particle.LAVA, location.clone().add(0.0, 1.5, 0.0), 1)
                                }
                                world.spawnParticle(Particle.LARGE_SMOKE, location, 1, 0.3, 0.3, 0.3, 0.0)
                            }
                            2 -> { // Fiery Crown
                                for (i in 0..5) {
                                    val angle_slice = (2 * Math.PI / 6) * i
                                    val x = location.x + 1.3 * Math.cos(angle_fast + angle_slice)
                                    val z = location.z + 1.3 * Math.sin(angle_fast + angle_slice)
                                    world.spawnParticle(Particle.FLAME, x, location.y + 1.5, z, 1, 0.0, 0.0, 0.0, 0.0)
                                }
                            }
                        }
                    }
                    "mythic" -> {
                        when(animationType) {
                            0 -> { // Ender Dragon Breath Ring
                                val radius = 1.8
                                val x = location.x + radius * Math.cos(angle_fast * -2)
                                val z = location.z + radius * Math.sin(angle_fast * -2)
                                world.spawnParticle(Particle.DRAGON_BREATH, x, location.y + 0.2, z, 3, 0.1, 0.1, 0.1, 0.0)
                                if (tick % 40 == 0L) world.playSound(location, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.5f, 1.2f)
                            }
                            1 -> { // Totem of Undying effect
                                if (tick % 60 == 0L) {
                                    world.spawnParticle(Particle.TOTEM_OF_UNDYING, location, 30, 0.5, 1.0, 0.5, 0.2)
                                    world.playSound(location, Sound.ITEM_TOTEM_USE, 0.8f, 1.0f)
                                }
                            }
                            2 -> { // Soul Vortex
                                val x = location.x + 0.5 * Math.cos(angle_fast * 4) * (1 - (tick % 100)/100.0)
                                val z = location.z + 0.5 * Math.sin(angle_fast * 4) * (1 - (tick % 100)/100.0)
                                val y = location.y + (tick % 100) * 0.02
                                world.spawnParticle(Particle.SOUL, x, y, z, 1, 0.0, 0.0, 0.0, 0.0)
                            }
                        }
                    }
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }
}
