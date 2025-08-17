package com.yourdomain.survivalplus.modules

import com.destroystokyo.paper.profile.PlayerProfile
import com.destroystokyo.paper.profile.ProfileProperty
import com.yourdomain.survivalplus.SurvivalPlus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.entity.*
import org.bukkit.entity.Display.Billboard
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.*
import kotlin.random.Random

class TreasuresModule(private val plugin: SurvivalPlus) : Module, Listener {

    private data class Treasure(
        val itemDisplay: ItemDisplay,
        val interaction: Interaction,
        val nameHologram: ArmorStand,
        val timerHologram: ArmorStand,
        val particleTask: BukkitTask,
        val despawnTime: Long,
        val loot: List<ItemStack>,
        val rarity: String
    )
    private val activeTreasures = mutableMapOf<Location, Treasure>()
    private val openGUIs = mutableMapOf<UUID, Location>()

    private val playerCooldowns = mutableMapOf<UUID, Long>()
    private var spawnChance = 0.001
    private var findCooldown = 60L
    private var despawnTimer = 60L
    private var broadcastSound = false
    private lateinit var rarityChances: Map<String, Double>
    private lateinit var messageSettings: Map<String, String>
    private lateinit var rarityHeadTextures: Map<String, String>

    private lateinit var gui: TreasuresGUI

    init {
        loadConfig()
    }

    fun loadConfig() {
        val config = plugin.configManager.getModuleConfig(getName().lowercase()) ?: return
        spawnChance = config.getDouble("global-settings.spawn-chance", 0.001)
        findCooldown = config.getLong("global-settings.find-cooldown", 60)
        despawnTimer = config.getLong("global-settings.despawn-timer", 60)
        broadcastSound = config.getBoolean("global-settings.broadcast-sound", false)

        rarityChances = config.getConfigurationSection("rarity-chances")?.getKeys(false)
            ?.associateWith { config.getDouble("rarity-chances.$it") }
            ?: mapOf("normal" to 0.50, "rare" to 0.25, "epic" to 0.15, "legendary" to 0.08, "mythic" to 0.02)

        messageSettings = config.getConfigurationSection("message-settings")?.getKeys(false)
            ?.associateWith { config.getString("message-settings.$it") ?: "player" }
            ?: mapOf("normal" to "player", "rare" to "player", "epic" to "server", "legendary" to "server", "mythic" to "server")

        rarityHeadTextures = config.getConfigurationSection("rarity-head-textures")?.getKeys(false)
            ?.associateWith { config.getString("rarity-head-textures.$it") ?: "" }
            ?: emptyMap()
    }

    override fun getName(): String = "treasures"
    override fun getDescription(): String = "Spawns treasure heads with biome-specific loot while mining."

    override fun enable() {
        gui = TreasuresGUI(plugin, this)
        plugin.server.pluginManager.registerEvents(this, plugin)
        loadConfig()
    }

    override fun disable() {
        org.bukkit.event.HandlerList.unregisterAll(this)
        activeTreasures.keys.toList().forEach { removeTreasure(it, emptyList()) }
    }

    fun isTreasure(block: Block): Boolean {
        return activeTreasures.containsKey(block.location)
    }

    fun trySpawnTreasure(player: Player, block: Block): Boolean {
        if (player.gameMode != GameMode.SURVIVAL || isTreasure(block)) return false
        val lastFound = playerCooldowns[player.uniqueId]
        if (lastFound != null && (System.currentTimeMillis() - lastFound) < findCooldown * 1000) return false
        if (Random.nextDouble() >= spawnChance) return false

        val rarity = getRandomRarity() ?: return false
        spawnTreasure(player, block, rarity)
        playerCooldowns[player.uniqueId] = System.currentTimeMillis()
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block

        if (isTreasure(block)) {
            event.isCancelled = true
            return
        }

        val ultimineModule = plugin.moduleManager.getModule("ultimine") as? UltimineModule
        if (ultimineModule != null && ultimineModule.isUltimineActive(player)) return

        if (trySpawnTreasure(player, block)) {
            event.isDropItems = false
            event.expToDrop = 0
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerInteract(event: PlayerInteractEntityEvent) {
        val player = event.player
        val entity = event.rightClicked
        if (entity !is Interaction) return

        val treasureLocation = activeTreasures.entries.find { (_, treasure) -> treasure.interaction == entity }?.key ?: return
        val treasure = activeTreasures[treasureLocation] ?: return

        event.isCancelled = true
        openGUIs[player.uniqueId] = treasureLocation
        gui.open(player, treasure.loot, treasure.rarity)
    }

    fun handleGUIClosure(player: Player, inventory: Inventory) {
        val location = openGUIs.remove(player.uniqueId) ?: return
        val remainingItems = inventory.contents.filterNotNull()
        removeTreasure(location, remainingItems)
    }

    private fun removeTreasure(location: Location, itemsToDrop: List<ItemStack>) {
        activeTreasures.remove(location)?.let {
            it.itemDisplay.remove()
            it.interaction.remove()
            it.nameHologram.remove()
            it.timerHologram.remove()
            it.particleTask.cancel()

            itemsToDrop.forEach { item ->
                location.world.dropItemNaturally(location, item)
            }
            location.block.type = Material.AIR
        }
    }

    private fun getRandomRarity(): String? {
        val totalWeight = rarityChances.values.sum()
        if (totalWeight <= 0) return null
        var random = Random.nextDouble() * totalWeight
        for ((rarity, weight) in rarityChances) {
            if (random < weight) return rarity
            random -= weight
        }
        return rarityChances.keys.lastOrNull()
    }

    private fun createPlayerHead(rarity: String): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD)
        val texture = rarityHeadTextures[rarity] ?: return head
        if (texture.isBlank()) return head

        val skullMeta = head.itemMeta as SkullMeta
        val profile = Bukkit.createProfile(UUID.randomUUID())
        profile.setProperty(ProfileProperty("textures", texture))
        skullMeta.playerProfile = profile
        head.itemMeta = skullMeta
        return head
    }

    private fun spawnTreasure(player: Player, block: Block, rarity: String) {
        val location = block.location
        val world = location.world ?: return
        block.type = Material.BARRIER

        val headStack = createPlayerHead(rarity)
        val itemDisplay = world.spawn(location.clone().add(0.5, 0.5, 0.5), ItemDisplay::class.java) {
            it.itemStack = headStack
            it.billboard = Billboard.FIXED
            it.teleportDuration = 0
            it.transformation = Transformation(
                Vector3f(0f, -0.5f, 0f), // translation
                AxisAngle4f(0f, 0f, 0f, 1f), // left rotation
                Vector3f(2f, 2f, 2f), // scale
                AxisAngle4f(0f, 0f, 0f, 1f) // right rotation
            )
        }

        val interaction = world.spawn(location.clone().add(0.5, 0.5, 0.5), Interaction::class.java) {
            it.interactionHeight = 1.0f
            it.interactionWidth = 1.0f
        }

        val holograms = spawnHolograms(location, rarity)
        val particleTask = startParticleEffect(player, location, rarity)
        val loot = generateLoot(rarity, block.biome.key.key)

        if (holograms != null) {
            val (nameHologram, timerHologram) = holograms
            val despawnTimeMillis = System.currentTimeMillis() + despawnTimer * 1000
            activeTreasures[location] = Treasure(itemDisplay, interaction, nameHologram, timerHologram, particleTask, despawnTimeMillis, loot, rarity)

            object : BukkitRunnable() {
                override fun run() {
                    if (activeTreasures.containsKey(location)) {
                        removeTreasure(location, emptyList())
                    }
                }
            }.runTaskLater(plugin, 20L * despawnTimer)

        } else {
            itemDisplay.remove()
            interaction.remove()
            particleTask.cancel()
            block.type = Material.AIR
        }

        plugin.logger.info("A '$rarity' treasure spawned for ${player.name}!")
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
            .append(Component.text(" treasure!", NamedTextColor.GRAY))

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

    private fun generateLoot(rarity: String, biome: String): List<ItemStack> {
        val config = plugin.configManager.getModuleConfig(getName().lowercase()) ?: return emptyList()
        val allLootRules = config.getStringList("loot-pool").mapNotNull { parseLootRule(it) }

        val applicableRules = allLootRules.filter { rule ->
            (rule.rarities.contains("all") || rule.rarities.contains(rarity)) &&
                    (rule.biomes.contains("all") || rule.biomes.contains(biome))
        }

        if (applicableRules.isEmpty()) {
            plugin.logger.warning("[Treasures] No applicable loot rules found for rarity '$rarity' in biome '$biome'.")
            return emptyList()
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

        val loot = mutableListOf<ItemStack>()
        repeat(itemCount) {
            if (itemsToGenerate.isEmpty()) return@repeat

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
            loot.add(item)
        }
        return loot
    }

    private fun spawnHolograms(location: Location, rarity: String): Pair<ArmorStand, ArmorStand>? {
        val nameLocation = location.clone().add(0.5, 1.2, 0.5)
        val timerLocation = location.clone().add(0.5, 0.9, 0.5)
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

    private fun startParticleEffect(player: Player, location: Location, rarity: String): BukkitTask {
        val effectLocation = location.clone().add(0.5, 0.5, 0.5)
        val world = effectLocation.world!!

        val (sound, pitch) = when (rarity.lowercase()) {
            "rare" -> Sound.BLOCK_ENCHANTMENT_TABLE_USE to 1.0f
            "epic" -> Sound.ENTITY_PLAYER_LEVELUP to 1.2f
            "legendary" -> Sound.UI_TOAST_CHALLENGE_COMPLETE to 1.0f
            "mythic" -> Sound.ENTITY_ENDER_DRAGON_DEATH to 0.8f
            else -> Sound.BLOCK_CHEST_OPEN to 1.0f
        }

        if (broadcastSound) {
            world.playSound(effectLocation, sound, 1.0f, pitch)
        } else {
            player.playSound(effectLocation, sound, 1.0f, pitch)
        }

        return object : BukkitRunnable() {
            var tick = 0L
            override fun run() {
                val treasure = activeTreasures[location]
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

                val angle_fast = tick * 6 * (Math.PI / 180) // Increased speed
                val angle_slow = tick * 0.8 * (Math.PI / 180) // Increased speed

                when (rarity.lowercase()) {
                    "normal" -> {
                        val y_offset = Math.sin(tick * 0.2) * 0.5 + 0.5
                        val x = effectLocation.x + 0.8 * Math.cos(angle_slow)
                        val z = effectLocation.z + 0.8 * Math.sin(angle_slow)
                        world.spawnParticle(Particle.HAPPY_VILLAGER, x, effectLocation.y + y_offset, z, 1, 0.0, 0.0, 0.0, 0.0)
                        if (tick % 10 == 0L) {
                            world.spawnParticle(Particle.CRIT, effectLocation, 30, 0.7, 0.7, 0.7, 0.15)
                        }
                    }
                    "rare" -> {
                        val radius = 1.8
                        val x = effectLocation.x + radius * Math.cos(angle_fast * 2)
                        val z = effectLocation.z + radius * Math.sin(angle_fast * 2)
                        Particle.DustOptions(Color.AQUA, 1.8f).let {
                            world.spawnParticle(Particle.DUST, x, effectLocation.y + 1.0, z, 5, it)
                        }
                        if (tick % 8 == 0L) {
                            world.spawnParticle(Particle.END_ROD, effectLocation, 25, 0.4, 0.8, 0.4, 0.15)
                            world.spawnParticle(Particle.GLOW, effectLocation, 10, 0.5, 0.5, 0.5, 0.1)
                        }
                    }
                    "epic" -> {
                        // Double helix
                        val radius = 1.5
                        val y = (tick % 50) / 50.0 * 2.5 // Ascending effect
                        val x1 = effectLocation.x + radius * Math.cos(angle_fast * 3 + y)
                        val z1 = effectLocation.z + radius * Math.sin(angle_fast * 3 + y)
                        Particle.DustOptions(Color.PURPLE, 2.2f).let {
                            world.spawnParticle(Particle.DUST, x1, effectLocation.y + y, z1, 3, it)
                        }
                        val x2 = effectLocation.x + radius * Math.cos(angle_fast * 3 + y + Math.PI)
                        val z2 = effectLocation.z + radius * Math.sin(angle_fast * 3 + y + Math.PI)
                        Particle.DustOptions(Color.FUCHSIA, 2.2f).let {
                            world.spawnParticle(Particle.DUST, x2, effectLocation.y + y, z2, 3, it)
                        }
                        if (tick % 6 == 0L) {
                            world.spawnParticle(Particle.ENCHANT, effectLocation, 15, 1.2, 1.2, 1.2, 0.15)
                        }
                    }
                    "legendary" -> {
                        // Pulsing shockwave and pillars of fire
                        if (tick % 20 == 0L) {
                            world.spawnParticle(Particle.EXPLOSION, effectLocation, 5, 1.0, 1.0, 1.0, 0.0)
                            Particle.DustOptions(Color.ORANGE, 3.0f).let {
                                world.spawnParticle(Particle.DUST_PLUME, effectLocation, 30, 1.5, 0.5, 1.5, it)
                            }
                        }
                        for (i in 0..4) {
                            val angle_slice = (2 * Math.PI / 5) * i
                            val x = effectLocation.x + 2.0 * Math.cos(angle_slice)
                            val z = effectLocation.z + 2.0 * Math.sin(angle_slice)
                            world.spawnParticle(Particle.FLAME, x, effectLocation.y + Math.sin(tick * 0.1 + i) * 0.5 + 1.0, z, 5, 0.1, 0.5, 0.1, 0.02)
                        }
                    }
                    "mythic" -> {
                        // Grand totem effect
                        if (tick % 50 == 0L) {
                            world.spawnParticle(Particle.TOTEM_OF_UNDYING, effectLocation, 100, 1.5, 2.0, 1.5, 0.5)
                            world.playSound(effectLocation, Sound.ITEM_TOTEM_USE, 1.2f, 1.4f)
                            world.playSound(effectLocation, Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.0f)
                        }
                        // Soulflame vortex sucking inwards
                        val radius = 2.5 * (1 - (tick % 100) / 100.0)
                        val y = (tick % 100) / 100.0 * 3.0
                        val x_vortex = effectLocation.x + radius * Math.cos(angle_fast * -4)
                        val z_vortex = effectLocation.z + radius * Math.sin(angle_fast * -4)
                        world.spawnParticle(Particle.SOUL, x_vortex, effectLocation.y + y, z_vortex, 3, 0.0, 0.0, 0.0, 0.0)

                        // Dragon's breath ground ring
                        val ring_radius = 2.2
                        val x_ring = effectLocation.x + ring_radius * Math.cos(angle_fast * 3)
                        val z_ring = effectLocation.z + ring_radius * Math.sin(angle_fast * 3)
                        world.spawnParticle(Particle.DRAGON_BREATH, x_ring, effectLocation.y + 0.2, z_ring, 10, 0.3, 0.1, 0.3, 0.1)
                    }
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }
}
