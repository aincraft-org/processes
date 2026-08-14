package dev.craftingmanager.paper;

import dev.craftingmanager.api.Domain.BlockKey;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.function.Consumer;

/** Reports affected runtime block identities; providers own registration state. */
public final class FunctionalBlockEvents implements Listener {
    private final Consumer<BlockKey> removed;
    private final Consumer<BlockKey> moved;

    public FunctionalBlockEvents(Consumer<BlockKey> removed, Consumer<BlockKey> moved) {
        this.removed = removed;
        this.moved = moved;
    }

    @EventHandler public void onBreak(BlockBreakEvent event) { removed.accept(key(event.getBlock())); }
    @EventHandler public void onBlockExplosion(BlockExplodeEvent event) { event.blockList().forEach(block -> removed.accept(key(block))); }
    @EventHandler public void onEntityExplosion(EntityExplodeEvent event) { event.blockList().forEach(block -> removed.accept(key(block))); }
    @EventHandler public void onExtend(BlockPistonExtendEvent event) { event.getBlocks().forEach(block -> moved.accept(key(block))); }
    @EventHandler public void onRetract(BlockPistonRetractEvent event) { event.getBlocks().forEach(block -> moved.accept(key(block))); }

    private static BlockKey key(Block block) { return ProcessInteractionListener.key(block); }
}
