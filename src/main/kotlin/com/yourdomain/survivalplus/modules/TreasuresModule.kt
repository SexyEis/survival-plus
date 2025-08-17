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
import org.bukkit.event.EventPriority
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

    private data class Treasure(
        val nameHologram: ArmorStand,
        val timerHologram: ArmorStand,
        val particleTask: BukkitTask,
        val despawnTime: Long
    )
    private val activeTreasures = mutableMapOf<Block, Treasure>()

    private val playerCooldowns = mutableMapOf<UUID, Long>()
    private var spawnChance = 0.001
    private var findCooldown = 60L // in seconds
    private var despawnTimer = 60L // in seconds
    private var broadcastSound = false
    private lateinit var rarityChances: Map<String, Double>
    private lateinit var messageSettings: Map<String, String>

    init {
        loadConfig()
    }

    fun loadConfig() {
        val config = plugin.configManager.getModuleConfig(getName().lowercase()) ?: return
        spawnChance = config.getDouble("global-settings.spawn-chance", 0.001)
        findCooldown = config.getLong("global-settings.find-cooldown", 60)
        despawnTimer = config.getLong("global-settings.despawn-timer", 60)
        broadcastSound = config.getBoolean("global-settings.broadcast-sound", false)

        val chancesSection = config.getConfigurationSection("rarity-chances")
        rarityChances = if (chancesSection != null) {
            chancesSection.getKeys(false).associateWith { chancesSection.getDouble(it) }
        } else {
            mapOf("normal" to 0.50, "rare" to 0.25, "epic" to 0.15, "legendary" to 0.08, "mythic" to 0.02)
        }

        val messageSection = config.getConfigurationSection("message-settings")
        messageSettings = if (messageSection != null) {
            messageSection.getKeys(false).associateWith { key -> messageSection.getString(key) ?: "player" }
        } else {
            mapOf("normal" to "player", "rare" to "player", "epic" to "server", "legendary" to "server", "mythic" to "server")
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

    fun trySpawnTreasure(player: Player, block: Block): Boolean {
        if (player.gameMode != GameMode.SURVIVAL || isTreasure(block)) {
            return false
        }

        val lastFound = playerCooldowns[player.uniqueId]
        if (lastFound != null && (System.currentTimeMillis() - lastFound) < findCooldown * 1000) {
            return false
        }

        if (Random.nextDouble() >= spawnChance) {
            return false
        }

        val rarity = getRandomRarity() ?: return false
        spawnTreasureChest(player, block, rarity)
        playerCooldowns[player.uniqueId] = System.currentTimeMillis()
        return true
    }

    private fun dropChestItems(block: Block) {
        val chest = block.state as? Chest
        if (chest != null) {
            chest.inventory.contents.forEach { item ->
                if (item != null) {
                    block.world.dropItemNaturally(block.location, item)
                }
            }
            chest.inventory.clear()
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block

        if (block.type == Material.CHEST && activeTreasures.containsKey(block)) {
            dropChestItems(block)
            removeTreasure(block, true)
            event.isDropItems = false
            return
        }

        val ultimineModule = plugin.moduleManager.getModule("ultimine") as? UltimineModule
        if (ultimineModule != null && ultimineModule.isUltimineActive(player)) {
            return // Ultimine will handle treasure spawning
        }

        if (trySpawnTreasure(player, block)) {
            event.isDropItems = false
            event.expToDrop = 0
        }
    }

    private fun removeTreasure(block: Block, broken: Boolean = false) {
        activeTreasures.remove(block)?.let {
            it.nameHologram.remove()
            it.timerHologram.remove()
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

        val holograms = spawnHolograms(block, rarity)
        val particleTask = startParticleEffect(player, block, rarity)

        if (holograms != null) {
            val (nameHologram, timerHologram) = holograms
            val despawnTimeMillis = System.currentTimeMillis() + despawnTimer * 1000
            activeTreasures[block] = Treasure(nameHologram, timerHologram, particleTask, despawnTimeMillis)
            populateChest(block, rarity)

            object : BukkitRunnable() {
                override fun run() {
                    if (activeTreasures.containsKey(block)) {
                        dropChestItems(block)
                        removeTreasure(block)
                    }
                }
            }.runTaskLater(plugin, 20L * despawnTimer)

        } else {
            block.type = originalType
            particleTask.cancel()
        }

        plugin.logger.info("A '$rarity' treasure chest spawned for ${player.name}!")
        sendMessage(player, rarity)
    }

    private fun sendMessage(player: Player, rarity: String) {
        val messageMode = messageSettings[rarity.lowercase()] ?: "player"
        if (messageMode == "none") return

        val (rarityText, color) = when (rarity.lowercase()) {
            "normal" -> "a Normal" to NamedTextColor.WHITE
            "rare" -> "a Rare" to NamedTextColor.BLUE
            "epic" -> "an Epic" to NamedTextColor.DARK_PURPLE
            "legendary" -> "a Legendary" to NamedTextColor.GOLD
            "mythic" -> "a Mythic" to NamedTextColor.LIGHT_PURPLE
            else -> "a" to NamedTextColor.GRAY
        }

        val message = Component.text(player.name, NamedTextColor.YELLOW)
            .append(Component.text(" has found ", NamedTextColor.GRAY))
            .append(Component.text(rarityText, color))
            .append(Component.text(" treasure chest!", NamedTextColor.GRAY))

        if (messageMode == "server") {
            plugin.server.broadcast(message)
        } else {
            player.sendMessage(message)
        }
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

    private fun spawnHolograms(block: Block, rarity: String): Pair<ArmorStand, ArmorStand>? {
        val nameLocation = block.location.add(0.5, 1.2, 0.5)
        val timerLocation = block.location.add(0.5, 0.9, 0.5)
        val world = nameLocation.world ?: return null

        val (text, color) = when (rarity.lowercase()) {
            "normal" -> "Normal Treasure" to NamedTextColor.WHITE
            "rare" -> "Rare Treasure" to NamedTextColor.BLUE
            "epic" -> "Epic Treasure" to NamedTextColor.DARK_PURPLE
            "legendary" -> "Legendary Treasure" to NamedTextColor.GOLD
            "mythic" -> "Mythic Treasure" to NamedTextColor.LIGHT_PURPLE
            else -> "Treasure" to NamedTextColor.GRAY
        }

        val nameHologram = world.spawn(nameLocation, ArmorStand::class.java) {
            it.isMarker = true
            it.isVisible = false
            it.setGravity(false)
            it.isSmall = true
            it.customName(Component.text(text).color(color).decorate(TextDecoration.BOLD))
            it.isCustomNameVisible = true
        }

        val timerHologram = world.spawn(timerLocation, ArmorStand::class.java) {
            it.isMarker = true
            it.isVisible = false
            it.setGravity(false)
            it.isSmall = true
            it.customName(Component.text("Despawns in...").color(NamedTextColor.GRAY))
            it.isCustomNameVisible = true
        }

        return nameHologram to timerHologram
    }

    private fun startParticleEffect(player: Player, block: Block, rarity: String): BukkitTask {
        val location = block.location.add(0.5, 0.5, 0.5)
        val world = location.world!!

        val (sound, pitch) = when (rarity.lowercase()) {
            "rare" -> Sound.BLOCK_ENCHANTMENT_TABLE_USE to 1.0f
            "epic" -> Sound.ENTITY_PLAYER_LEVELUP to 1.2f
            "legendary" -> Sound.UI_TOAST_CHALLENGE_COMPLETE to 1.0f
            "mythic" -> Sound.ENTITY_ENDER_DRAGON_DEATH to 0.8f
            else -> Sound.BLOCK_CHEST_OPEN to 1.0f
        }

        if (broadcastSound) {
            world.playSound(location, sound, 1.0f, pitch)
        } else {
            player.playSound(location, sound, 1.0f, pitch)
        }

        val animationType = Random.nextInt(3)

        return object : BukkitRunnable() {
            var tick = 0L
            override fun run() {
                val treasure = activeTreasures[block]
                if (treasure == null) {
                    this.cancel()
                    return
                }

                if (tick % 20 == 0L) {
                    val remainingSeconds = (treasure.despawnTime - System.currentTimeMillis()) / 1000
                    if (remainingSeconds > 0) {
                        treasure.timerHologram.customName(
                            Component.text("Despawns in: ", NamedTextColor.GRAY)
                                .append(Component.text(remainingSeconds, NamedTextColor.WHITE))
                                .append(Component.text("s", NamedTextColor.GRAY))
                        )
                    } else {
                        treasure.timerHologram.customName(Component.text("Despawning...", NamedTextColor.RED))
                    }
                }

                val angle_fast = tick * 5 * (Math.PI / 180)
                val angle_slow = tick * 0.5 * (Math.PI / 180)

                when (rarity.lowercase()) {
                    "normal" -> {
                        if (tick % 8 == 0L) {
                            world.spawnParticle(Particle.CRIT, location, 20, 0.6, 0.6, 0.6, 0.1)
                            world.spawnParticle(Particle.HAPPY_VILLAGER, location, 10, 0.6, 0.6, 0.6, 0.1)
                        }
                    }
                    "rare" -> {
                        val x = location.x + 1.5 * Math.cos(angle_fast * 1.5)
                        val z = location.z + 1.5 * Math.sin(angle_fast * 1.5)
                        Particle.DustOptions(Color.AQUA, 1.5f).let {
                            world.spawnParticle(Particle.DUST, x, location.y + 0.8, z, 2, it)
                        }
                        if (tick % 10 == 0L) {
                            world.spawnParticle(Particle.END_ROD, location, 15, 0.2, 0.6, 0.2, 0.1)
                        }
                    }
                    "epic" -> {
                        val x1 = location.x + 1.2 * Math.cos(angle_fast * 2.5)
                        val z1 = location.z + 1.2 * Math.sin(angle_fast * 2.5)
                        val y1 = location.y + (tick % 40) * 0.06
                        Particle.DustOptions(Color.PURPLE, 2.0f).let {
                            world.spawnParticle(Particle.DUST, Location(world, x1, y1, z1), 2, it)
                        }
                        val x2 = location.x + 1.2 * Math.cos(angle_fast * 2.5 + Math.PI)
                        val z2 = location.z + 1.2 * Math.sin(angle_fast * 2.5 + Math.PI)
                        Particle.DustOptions(Color.FUCHSIA, 2.0f).let {
                            world.spawnParticle(Particle.DUST, Location(world, x2, y1, z2), 2, it)
                        }
                        if (tick % 5 == 0L) {
                            world.spawnParticle(Particle.ENCHANT, location, 5, 1.0, 1.0, 1.0, 0.1)
                        }
                    }
                    "legendary" -> {
                        for (i in 0..6) {
                            val angle_slice = (2 * Math.PI / 7) * i
                            val x = location.x + 1.8 * Math.cos(angle_fast * -1.5 + angle_slice)
                            val z = location.z + 1.8 * Math.sin(angle_fast * -1.5 + angle_slice)
                            world.spawnParticle(Particle.FLAME, x, location.y + 1.0, z, 1, 0.0, 0.0, 0.0, 0.0)
                        }
                        if (tick % 4 == 0L) {
                            Particle.DustOptions(Color.ORANGE, 2.5f).let {
                                world.spawnParticle(Particle.DUST, location, 15, 0.6, 0.6, 0.6, it)
                            }
                        }
                    }
                    "mythic" -> {
                        // Totem effect
                        if (tick % 40 == 0L) {
                            world.spawnParticle(Particle.TOTEM_OF_UNDYING, location, 50, 0.8, 1.2, 0.8, 0.3)
                            world.playSound(location, Sound.ITEM_TOTEM_USE, 1.0f, 1.2f)
                        }
                        // Dragon breath ring
                        val radius = 2.0
                        val x = location.x + radius * Math.cos(angle_fast * -2.5)
                        val z = location.z + radius * Math.sin(angle_fast * -2.5)
                        world.spawnParticle(Particle.DRAGON_BREATH, x, location.y + 0.2, z, 5, 0.2, 0.2, 0.2, 0.1)
                        // Soul vortex
                        val x_vortex = location.x + 0.8 * Math.cos(angle_fast * 5) * (1 - (tick % 80)/80.0)
                        val z_vortex = location.z + 0.8 * Math.sin(angle_fast * 5) * (1 - (tick % 80)/80.0)
                        val y_vortex = location.y + (tick % 80) * 0.03
                        world.spawnParticle(Particle.SOUL, x_vortex, y_vortex, z_vortex, 2, 0.0, 0.0, 0.0, 0.0)
                    }
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }
}
