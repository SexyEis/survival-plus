package com.yourdomain.survivalplus

import com.yourdomain.survivalplus.managers.CommandManager
import com.yourdomain.survivalplus.managers.ConfigManager
import com.yourdomain.survivalplus.managers.GUIManager
import com.yourdomain.survivalplus.managers.ModuleManager
import com.yourdomain.survivalplus.modules.*
import org.bukkit.plugin.java.JavaPlugin

class SurvivalPlus : JavaPlugin() {

    lateinit var configManager: ConfigManager
    lateinit var moduleManager: ModuleManager
    lateinit var commandManager: CommandManager
    lateinit var guiManager: GUIManager

    override fun onEnable() {
        // Initialize managers
        configManager = ConfigManager(this)
        moduleManager = ModuleManager(this)
        commandManager = CommandManager(this)
        guiManager = GUIManager(this)

        // Setup configuration
        configManager.setup()

        // Register modules
        moduleManager.registerModule(JoinQuitMessageModule(this))
        moduleManager.registerModule(VeinMinerModule(this))
        moduleManager.registerModule(TimberModule(this))
        moduleManager.registerModule(UltimineModule(this))
        moduleManager.registerModule(ToolStorageModule(this))
        moduleManager.registerModule(AntiCooldownModule(this))

        // Enable modules based on config
        moduleManager.initialModuleToggle()

        // Register commands
        commandManager.registerCommands()

        // Register GUI listeners
        guiManager.registerGUI()

        logger.info("SurvivalPlus has been enabled!")
    }

    override fun onDisable() {
        // Disable all modules
        moduleManager.disableModules()

        logger.info("SurvivalPlus has been disabled!")
    }
}
