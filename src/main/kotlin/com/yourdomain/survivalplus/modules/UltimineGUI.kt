package com.yourdomain.survivalplus.modules

import com.yourdomain.survivalplus.SurvivalPlus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class UltimineGUI(private val plugin: SurvivalPlus, private val ultimineModule: UltimineModule) : Listener {

    private val inventoryTitle = Component.text("Ultimine Settings")
    private val modeKey = NamespacedKey(plugin, "ultimine_mode")
    private val actionKey = NamespacedKey(plugin, "ultimine_action")

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun open(player: Player) {
        val inventory = Bukkit.createInventory(player, 9, inventoryTitle)

        // Create items for each mode
        inventory.setItem(0, createModeItem(player, UltimineModule.Mode.NORMAL, Material.IRON_PICKAXE, "Normal", "Mines connected blocks of the same type."))
        inventory.setItem(1, createModeItem(player, UltimineModule.Mode.TUNNEL, Material.IRON_SHOVEL, "Tunnel", "Mines a 2x1 tunnel."))
        inventory.setItem(2, createModeItem(player, UltimineModule.Mode.BIG_TUNNEL, Material.DIAMOND_SHOVEL, "Big Tunnel", "Mines a 3x3 tunnel."))

        // Create toggle item
        inventory.setItem(8, createToggleItem(player))

        player.openInventory(inventory)
    }

    private fun createModeItem(player: Player, mode: UltimineModule.Mode, material: Material, name: String, description: String): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta?.displayName(Component.text(name, NamedTextColor.GREEN))
        val lore = mutableListOf<Component>()
        lore.add(Component.text(description, NamedTextColor.GRAY))
        meta?.lore(lore)
        meta?.persistentDataContainer?.set(modeKey, PersistentDataType.STRING, mode.name)

        if (ultimineModule.getPlayerMode(player) == mode) {
            meta?.addEnchant(Enchantment.UNBREAKING, 1, true)
            meta?.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        }

        item.itemMeta = meta
        return item
    }

    private fun createToggleItem(player: Player): ItemStack {
        val isActive = ultimineModule.isUltimineActive(player)
        val material = if (isActive) Material.LIME_DYE else Material.GRAY_DYE
        val name = if (isActive) "Ultimine: ON" else "Ultimine: OFF"
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta?.displayName(Component.text(name, if(isActive) NamedTextColor.GREEN else NamedTextColor.RED))
        meta?.persistentDataContainer?.set(actionKey, PersistentDataType.STRING, "toggle")
        item.itemMeta = meta
        return item
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.title() != inventoryTitle) return
        event.isCancelled = true

        val clickedItem = event.currentItem ?: return
        val player = event.whoClicked as? Player ?: return

        val modeName = clickedItem.itemMeta?.persistentDataContainer?.get(modeKey, PersistentDataType.STRING)
        if (modeName != null) {
            val mode = UltimineModule.Mode.valueOf(modeName)
            ultimineModule.setPlayerMode(player, mode)
            player.closeInventory()
            player.sendActionBar(Component.text("Ultimine mode set to: ", NamedTextColor.GRAY).append(Component.text(mode.name, NamedTextColor.GREEN)))
            return
        }

        val action = clickedItem.itemMeta?.persistentDataContainer?.get(actionKey, PersistentDataType.STRING)
        if (action == "toggle") {
            ultimineModule.toggleUltimineActive(player)
            open(player) // Re-open to update the GUI
        }
    }
}
