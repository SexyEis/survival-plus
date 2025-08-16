package com.yourdomain.survivalplus.modules

import com.yourdomain.survivalplus.SurvivalPlus
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.*
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.event.entity.EntityDamageByEntityEvent

class AntiCooldownModule(private val plugin: SurvivalPlus) : Module, Listener {

    private val modifier = AttributeModifier(UUID.fromString("fa233e7c-4180-4865-b01b-2b2dbdb352cf"), "generic.attack_speed", 100.0, AttributeModifier.Operation.ADD_NUMBER)
    private val damageValues = mapOf(
        Material.WOODEN_SWORD to 4.0,
        Material.STONE_SWORD to 5.0,
        Material.IRON_SWORD to 6.0,
        Material.DIAMOND_SWORD to 7.0,
        Material.GOLDEN_SWORD to 4.0,
        Material.WOODEN_AXE to 3.0,
        Material.STONE_AXE to 4.0,
        Material.IRON_AXE to 5.0,
        Material.DIAMOND_AXE to 6.0,
        Material.GOLDEN_AXE to 3.0,
        Material.WOODEN_PICKAXE to 2.0,
        Material.STONE_PICKAXE to 3.0,
        Material.IRON_PICKAXE to 4.0,
        Material.DIAMOND_PICKAXE to 5.0,
        Material.GOLDEN_PICKAXE to 2.0,
        Material.WOODEN_SHOVEL to 1.0,
        Material.STONE_SHOVEL to 2.0,
        Material.IRON_SHOVEL to 3.0,
        Material.DIAMOND_SHOVEL to 4.0,
        Material.GOLDEN_SHOVEL to 1.0,
        Material.WOODEN_HOE to 1.0,
        Material.STONE_HOE to 1.0,
        Material.IRON_HOE to 1.0,
        Material.DIAMOND_HOE to 1.0,
        Material.GOLDEN_HOE to 1.0
    )

    override fun getName(): String = "anticooldown"
    override fun getDescription(): String = "Removes attack cooldown and reverts weapon damage to 1.8 values."

    override fun enable() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.onlinePlayers.forEach { addModifier(it) }
    }

    override fun disable() {
        org.bukkit.event.HandlerList.unregisterAll(this)
        plugin.server.onlinePlayers.forEach { removeModifier(it) }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        addModifier(event.player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        removeModifier(event.player)
    }

    @EventHandler
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        if (event.damager.type != EntityType.PLAYER) {
            return
        }

        val player = event.damager as Player
        val item = player.inventory.itemInMainHand
        val material = item.type

        if (material == Material.MACE || material == Material.CROSSBOW) {
            return
        }

        damageValues[material]?.let {
            event.damage = it
        }
    }

    private fun addModifier(player: Player) {
        val attribute = player.getAttribute(Attribute.GENERIC_ATTACK_SPEED)
        attribute?.modifiers?.forEach {
            if (it.name == modifier.name) {
                attribute.removeModifier(it)
            }
        }
        attribute?.addModifier(modifier)
    }

    private fun removeModifier(player: Player) {
        val attribute = player.getAttribute(Attribute.GENERIC_ATTACK_SPEED)
        attribute?.modifiers?.forEach {
            if (it.name == modifier.name) {
                attribute.removeModifier(it)
            }
        }
    }
}
