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

import java.util.List;

public final class FirstPartyStationListener implements Listener {
    private record Host(Material material, String blockId) {}

    private static final List<Host> HOSTS = List.of(
            new Host(Material.BLAST_FURNACE, ExampleProcessProvider.BLOCK_ID),
            new Host(Material.GRINDSTONE, ExtraProcessProvider.POLISH_BLOCK_ID),
            new Host(Material.CAULDRON, ExtraProcessProvider.MIX_BLOCK_ID)
    );

    private final CraftingManagerApi api;
    private final FirstPartyContent content;

    public FirstPartyStationListener(CraftingManagerApi api, FirstPartyContent content) {
        this.api = api;
        this.content = content;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Host host = hostForMaterial(block.getType());
        if (host == null || api.functionalBlockDefinition(host.blockId()).isEmpty()) return;
        api.placeFunctionalBlock(ProcessInteractionListener.key(block), host.blockId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        BlockKey key = ProcessInteractionListener.key(block);
        Host host = hostForMaterial(block.getType());
        String placed = api.placedFunctionalBlock(key).orElse(null);
        if (host == null) {
            host = hostForBlockId(placed);
        }
        if (host == null) return;
        if (placed == null && api.functionalBlockDefinition(host.blockId()).isPresent()) {
            api.placeFunctionalBlock(key, host.blockId());
        }
        event.setCancelled(true);
        content.openGui(player, key);
    }

    private static Host hostForMaterial(Material material) {
        for (Host host : HOSTS) {
            if (host.material() == material) return host;
        }
        return null;
    }

    private static Host hostForBlockId(String blockId) {
        if (blockId == null) return null;
        for (Host host : HOSTS) {
            if (host.blockId().equals(blockId)) return host;
        }
        return null;
    }
}
