package dev.craftingmanager.paper;

import dev.craftingmanager.runtime.RuntimeEngine;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.Objects;

/** Tracks loaded chunks so processes tick only while their host chunk is loaded. */
public final class ProcessChunkListener implements Listener {
    private final RuntimeEngine engine;

    public ProcessChunkListener(RuntimeEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @EventHandler
    public void onLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        engine.loadChunk(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }

    @EventHandler
    public void onUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        engine.unloadChunk(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }
}
