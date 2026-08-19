package dev.craftingmanager.paper;

import dev.craftingmanager.api.ItemAccess;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.api.ItemVault;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class PlayerItemVault implements ItemVault {
    @Override public Optional<ItemAccess> of(UUID owner) {
        Player player = Bukkit.getPlayer(owner);
        if (player == null || !player.isOnline()) return Optional.empty();
        return Optional.of(new PlayerInventoryAccess(player.getInventory()));
    }

    private static final class PlayerInventoryAccess implements ItemAccess {
        private final PlayerInventory inventory;

        private PlayerInventoryAccess(PlayerInventory inventory) {
            this.inventory = inventory;
        }

        @Override public int size() { return inventory.getStorageContents().length; }

        @Override public ItemSnapshot get(int slot) {
            return snapshot(inventory.getStorageContents()[slot]);
        }

        @Override public void set(int slot, ItemSnapshot stack) {
            ItemStack[] contents = inventory.getStorageContents();
            contents[slot] = stack == null ? null : new ItemStack(Material.valueOf(stack.material()), stack.amount());
            inventory.setStorageContents(contents);
        }

        private static ItemSnapshot snapshot(ItemStack stack) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return null;
            return new ItemSnapshot(stack.getType().name().toUpperCase(Locale.ROOT), stack.getAmount(), null);
        }
    }
}
