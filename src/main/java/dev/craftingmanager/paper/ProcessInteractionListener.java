package dev.craftingmanager.paper;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public final class ProcessInteractionListener implements Listener {
    private final CraftingManagerApi api;

    public ProcessInteractionListener(CraftingManagerApi api) { this.api = api; }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        api.trigger(key(event.getClickedBlock()), player.getUniqueId());
    }

    public static BlockKey key(Block block) {
        if (block == null) throw new IllegalArgumentException("block is required");
        return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
}
