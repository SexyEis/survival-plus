package com.yourdomain.survivalplus.managers

import com.yourdomain.survivalplus.SurvivalPlus
import com.yourdomain.survivalplus.modules.Module

class ModuleManager(private val plugin: SurvivalPlus) {

    val modules = mutableMapOf<String, Module>()

    fun registerModule(module: Module) {
        modules[module.getName().lowercase()] = module
    }

    fun initialModuleToggle() {
        val config = plugin.configManager.mainConfig
        modules.values.forEach { module ->
            val moduleName = module.getName().lowercase()
            if (config.getBoolean("modules.$moduleName.enabled", true)) {
                plugin.configManager.loadModuleConfig(moduleName)
                module.enable()
                plugin.logger.info("Module '${module.getName()}' enabled.")
            } else {
                module.disable()
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
        val config = plugin.configManager.mainConfig
        val isEnabled = config.getBoolean("modules.$moduleName.enabled", true)

        config.set("modules.$moduleName.enabled", !isEnabled)
        plugin.saveConfig()

        if (!isEnabled) {
            plugin.configManager.loadModuleConfig(moduleName)
            module.enable()
            plugin.logger.info("Module '${module.getName()}' has been enabled.")
        } else {
            module.disable()
            plugin.configManager.unloadModuleConfig(moduleName)
            plugin.logger.info("Module '${module.getName()}' has been disabled.")
        }
        return true
    }

    fun isModuleEnabled(name: String): Boolean {
        val moduleName = name.lowercase()
        return plugin.configManager.mainConfig.getBoolean("modules.$moduleName.enabled", true)
    }

    fun reloadModules() {
        plugin.configManager.reloadMainConfig()

        modules.values.forEach { module ->
            val moduleName = module.getName().lowercase()
            val wasEnabled = isModuleEnabled(moduleName)
            val shouldBeEnabled = plugin.configManager.mainConfig.getBoolean("modules.$moduleName.enabled", true)

            when {
                !wasEnabled && shouldBeEnabled -> {
                    plugin.configManager.loadModuleConfig(moduleName)
                    module.enable()
                    plugin.logger.info("Module '${module.getName()}' enabled.")
                }
                wasEnabled && !shouldBeEnabled -> {
                    module.disable()
                    plugin.configManager.unloadModuleConfig(moduleName)
                    plugin.logger.info("Module '${module.getName()}' disabled.")
                }
                wasEnabled && shouldBeEnabled -> {
                    // Reload the module by disabling and enabling it
                    module.disable()
                    plugin.configManager.unloadModuleConfig(moduleName)
                    plugin.configManager.loadModuleConfig(moduleName)
                    module.enable()
                    plugin.logger.info("Module '${module.getName()}' reloaded.")
                }
            }
        }
    }
}
