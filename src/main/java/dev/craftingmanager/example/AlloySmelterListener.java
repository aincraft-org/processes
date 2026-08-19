package dev.craftingmanager.example;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.paper.ProcessInteractionListener;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public final class AlloySmelterListener implements Listener {
    private final CraftingManagerApi api;
    private final ExampleProcessProvider provider;

    public AlloySmelterListener(CraftingManagerApi api, ExampleProcessProvider provider) {
        this.api = api;
        this.provider = provider;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.BLAST_FURNACE) return;
        if (api.functionalBlockDefinition(ExampleProcessProvider.BLOCK_ID).isEmpty()) return;
        api.placeFunctionalBlock(ProcessInteractionListener.key(block), ExampleProcessProvider.BLOCK_ID);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        BlockKey key = ProcessInteractionListener.key(block);
        boolean smelter = block.getType() == Material.BLAST_FURNACE
                || ExampleProcessProvider.BLOCK_ID.equals(api.placedFunctionalBlock(key).orElse(null));
        if (!smelter) return;
        if (api.placedFunctionalBlock(key).isEmpty()
                && api.functionalBlockDefinition(ExampleProcessProvider.BLOCK_ID).isPresent()) {
            api.placeFunctionalBlock(key, ExampleProcessProvider.BLOCK_ID);
        }
        event.setCancelled(true);
        provider.openGui(player, key);
    }
}
