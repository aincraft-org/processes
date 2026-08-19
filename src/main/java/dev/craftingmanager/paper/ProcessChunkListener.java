package dev.craftingmanager.paper;

import dev.craftingmanager.runtime.RuntimeEngine;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.Objects;

/** Flushes furnace-like cook progress when a chunk unloads. */
public final class ProcessChunkListener implements Listener {
    private final RuntimeEngine engine;

    public ProcessChunkListener(RuntimeEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @EventHandler
    public void onUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        engine.persistChunk(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }
}
