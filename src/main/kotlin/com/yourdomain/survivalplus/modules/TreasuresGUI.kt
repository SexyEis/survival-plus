package com.yourdomain.survivalplus.modules

import com.yourdomain.survivalplus.SurvivalPlus
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

class TreasuresGUI(private val plugin: SurvivalPlus, private val treasuresModule: TreasuresModule) : Listener, InventoryHolder {

    override fun getInventory(): Inventory {
        // This is a dummy inventory because we use this class as a holder for many dynamic inventories.
        return Bukkit.createInventory(this, 9)
    }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun open(player: Player, loot: List<ItemStack>, rarity: String) {
        val size = ((loot.size + 8) / 9) * 9
        val title = Component.text(rarity.replaceFirstChar { it.uppercase() } + " Treasure")
        val inventory = Bukkit.createInventory(this, size, title)
        inventory.addItem(*loot.toTypedArray())
        player.openInventory(inventory)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.inventory.holder !is TreasuresGUI) return

        // Player is trying to place an item into the treasure GUI
        if (event.clickedInventory === event.view.topInventory) {
            if (event.action == InventoryAction.PLACE_ALL ||
                event.action == InventoryAction.PLACE_ONE ||
                event.action == InventoryAction.PLACE_SOME ||
                event.action == InventoryAction.SWAP_WITH_CURSOR) {
                event.isCancelled = true
            }
        }
        // Player is trying to shift-click an item from their inventory into the treasure GUI
        else if (event.clickedInventory === event.view.bottomInventory && event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        if (event.inventory.holder !is TreasuresGUI) return

        val player = event.player as Player
        treasuresModule.handleGUIClosure(player, event.inventory)
    }
}
