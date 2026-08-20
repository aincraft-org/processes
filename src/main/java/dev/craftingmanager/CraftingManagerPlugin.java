package dev.craftingmanager;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.example.ExampleGuiListener;
import dev.craftingmanager.example.FirstPartyContent;
import dev.craftingmanager.example.FirstPartyStationListener;
import dev.craftingmanager.paper.BoltLockableHook;
import dev.craftingmanager.paper.BoltProtectionAccess;
import dev.craftingmanager.paper.FunctionalBlockEvents;
import dev.craftingmanager.paper.HopperIoListener;
import dev.craftingmanager.paper.PaperProcessEventSink;
import dev.craftingmanager.paper.PlayerItemVault;
import dev.craftingmanager.paper.ProcessChunkListener;
import dev.craftingmanager.paper.ProcessInteractionListener;
import dev.craftingmanager.persistence.SqliteProcessStore;
import dev.craftingmanager.runtime.ItemOutputHandler;
import dev.craftingmanager.runtime.RuntimeEngine;
import dev.craftingmanager.runtime.SlotInventoryAdapter;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class CraftingManagerPlugin extends JavaPlugin {
    private SqliteProcessStore store;
    private RuntimeEngine engine;
    private FirstPartyContent firstParty;
    private BoltLockableHook boltHook;
    private BukkitTask tickTask;

    @Override public void onEnable() {
        getDataFolder().mkdirs();
        store = SqliteProcessStore.open(getDataFolder().toPath().resolve("craftingmanager.db"));
        engine = new RuntimeEngine(store, new PaperProcessEventSink(new BoltProtectionAccess()));
        PlayerItemVault vault = new PlayerItemVault();
        engine.registerInventoryAdapter(new SlotInventoryAdapter(vault));
        engine.registerEffectHandler(new ItemOutputHandler(vault, engine));
        ExampleGuiListener guiListener = new ExampleGuiListener();
        firstParty = new FirstPartyContent(engine, guiListener);
        boltHook = new BoltLockableHook(engine);
        engine.onLockableRegistered(boltHook::register);
        firstParty.enable();
        boltHook.install();
        engine.hydrate();
        markLoadedChunks();
        tickTask = getServer().getScheduler().runTaskTimer(this, engine::tick, 1L, 1L);
        getServer().getServicesManager().register(CraftingManagerApi.class, engine, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new ProcessInteractionListener(engine), this);
        getServer().getPluginManager().registerEvents(new FunctionalBlockEvents(engine::invalidateBlock, engine::invalidateBlock), this);
        getServer().getPluginManager().registerEvents(guiListener, this);
        getServer().getPluginManager().registerEvents(new FirstPartyStationListener(engine, firstParty), this);
        getServer().getPluginManager().registerEvents(new HopperIoListener(engine), this);
        getServer().getPluginManager().registerEvents(new ProcessChunkListener(engine), this);
    }

    @Override public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        if (firstParty != null) firstParty.disable();
        if (boltHook != null) boltHook.uninstall();
        if (engine != null) engine.shutdown();
        getServer().getServicesManager().unregister(CraftingManagerApi.class, this);
        if (store != null) store.close();
    }

    private void markLoadedChunks() {
        for (World world : getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                engine.loadChunk(world.getUID(), chunk.getX(), chunk.getZ());
            }
        }
    }
}
