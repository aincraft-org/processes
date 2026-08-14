package dev.craftingmanager;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.paper.FunctionalBlockEvents;
import dev.craftingmanager.paper.ProcessInteractionListener;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class CraftingManagerPlugin extends JavaPlugin {
    private RuntimeEngine engine;

    @Override public void onEnable() {
        engine = new RuntimeEngine();
        getServer().getServicesManager().register(CraftingManagerApi.class, engine, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new ProcessInteractionListener(engine), this);
        getServer().getPluginManager().registerEvents(new FunctionalBlockEvents(engine::invalidateBlock, engine::invalidateBlock), this);
    }

    @Override public void onDisable() {
        if (engine != null) engine.clear();
        getServer().getServicesManager().unregister(CraftingManagerApi.class, this);
    }
}
