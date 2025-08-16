package com.yourdomain.survivalplus.managers

import com.yourdomain.survivalplus.SurvivalPlus
import com.yourdomain.survivalplus.modules.Module

class ModuleManager(private val plugin: SurvivalPlus) {

    val modules = mutableMapOf<String, Module>()

    fun registerModule(module: Module) {
        modules[module.getName().lowercase()] = module
    }

    fun initialModuleToggle() {
        val config = plugin.configManager.getConfig()
        modules.values.forEach { module ->
            val moduleName = module.getName().lowercase()
            if (config.getBoolean("modules.$moduleName.enabled", true)) {
                module.enable()
                plugin.logger.info("Module '${module.getName()}' enabled.")
            } else {
                module.disable() // Make sure to call disable so listeners are unregistered etc.
                plugin.logger.info("Module '${module.getName()}' is disabled by config.")
            }
        }
    }

    fun disableModules() {
        modules.values.forEach { it.disable() }
    }

    fun getModule(name: String): Module? {
        return modules[name.lowercase()]
    }

    fun getModules(): List<Module> {
        return modules.values.toList()
    }

    fun toggleModule(name: String): Boolean {
        val module = getModule(name) ?: return false
        val moduleName = module.getName().lowercase()
        val config = plugin.configManager.getConfig()
        val isEnabled = config.getBoolean("modules.$moduleName.enabled", true)

        config.set("modules.$moduleName.enabled", !isEnabled)
        plugin.saveConfig()
        // No need to call plugin.configManager.reload() here, the config object is mutable

        if (!isEnabled) {
            module.enable()
            plugin.logger.info("Module '${module.getName()}' has been enabled.")
        } else {
            module.disable()
            plugin.logger.info("Module '${module.getName()}' has been disabled.")
        }
        return true
    }

    fun isModuleEnabled(name: String): Boolean {
        val module = getModule(name) ?: return false
        return plugin.configManager.getConfig().getBoolean("modules.${module.getName().lowercase()}.enabled", true)
    }
}
