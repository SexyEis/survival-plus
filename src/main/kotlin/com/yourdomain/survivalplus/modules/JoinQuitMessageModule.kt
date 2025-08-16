package com.yourdomain.survivalplus.modules

import com.yourdomain.survivalplus.SurvivalPlus
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class JoinQuitMessageModule(private val plugin: SurvivalPlus) : Module, Listener {

    override fun getName(): String {
        return "join-quit-message"
    }

    override fun getDescription(): String {
        return "Disables the join and quit messages."
    }

    override fun enable() {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    override fun disable() {
        HandlerList.unregisterAll(this)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        event.joinMessage = null
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        event.quitMessage = null
    }
}
