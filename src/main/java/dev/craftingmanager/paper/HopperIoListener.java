package dev.craftingmanager.paper;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ProcessFace;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.runtime.HopperGeometry;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Optional;

public final class HopperIoListener implements Listener {
    private final CraftingManagerApi api;

    public HopperIoListener(CraftingManagerApi api) {
        this.api = api;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        Block destination = blockOf(event.getDestination());
        Block source = blockOf(event.getSource());
        if (destination != null && isStation(destination) && isHopper(source)) {
            ProcessFace face = HopperGeometry.insertionFace(facing(source));
            ItemSnapshot snapshot = snapshot(event.getItem());
            if (snapshot == null) return;
            if (!api.insertAt(ProcessInteractionListener.key(destination), face, snapshot)) return;
            event.setCancelled(true);
            event.getSource().removeItem(event.getItem());
            return;
        }
        if (source != null && isStation(source) && isHopper(destination)) {
            event.setCancelled(true);
            Optional<ItemSnapshot> taken = api.extractAt(
                    ProcessInteractionListener.key(source), HopperGeometry.extractionFace(), event.getItem().getAmount());
            taken.ifPresent(item -> event.getDestination().addItem(stack(item)));
        }
    }

    private boolean isStation(Block block) {
        return api.placedFunctionalBlock(ProcessInteractionListener.key(block)).isPresent();
    }

    private static boolean isHopper(Block block) {
        return block != null && block.getType() == Material.HOPPER;
    }

    private static ProcessFace facing(Block hopper) {
        if (hopper.getBlockData() instanceof Directional directional) {
            return ProcessFace.valueOf(directional.getFacing().name());
        }
        return ProcessFace.DOWN;
    }

    private static Block blockOf(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof BlockState state) return state.getBlock();
        return null;
    }

    private static ItemSnapshot snapshot(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return null;
        return new ItemSnapshot(stack.getType().name().toUpperCase(Locale.ROOT), stack.getAmount(), null);
    }

    private static ItemStack stack(ItemSnapshot snapshot) {
        return new ItemStack(Material.valueOf(snapshot.material()), snapshot.amount());
    }
}
