package dev.craftingmanager.example;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.ItemSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Provider-owned example inventory view for one process. */
public final class ExampleProcessGui {
    public static final String TITLE = "Alloy Smelter";
    public static final int SIZE = 27;
    public static final int IRON_SLOT = 10;
    public static final int COAL_SLOT = 12;
    public static final int OUTPUT_SLOT = 14;
    public static final int START_SLOT = 16;

    private final CraftingManagerApi api;
    private final BlockKey block;
    private final UUID owner;
    private final String processId;
    private Inventory inventory;
    private String message;

    public ExampleProcessGui(CraftingManagerApi api, BlockKey block, UUID owner, String processId) {
        this.api = Objects.requireNonNull(api);
        this.block = Objects.requireNonNull(block);
        this.owner = Objects.requireNonNull(owner);
        this.processId = requireText(processId, "processId");
    }

    public void open(Player player) {
        Objects.requireNonNull(player);
        if (!player.getUniqueId().equals(owner)) throw new IllegalArgumentException("player does not own this GUI");
        inventory = Bukkit.createInventory(null, SIZE, TITLE);
        render();
        player.openInventory(inventory);
    }

    public boolean owns(Inventory candidate) {
        return inventory != null && inventory.equals(candidate);
    }

    public boolean handleClick(int rawSlot) {
        if (rawSlot != START_SLOT) return false;
        CraftingManagerApi.ProcessStartResult result = api.start(block, processId, owner);
        message = result.started() ? "Process started" : result.reason();
        if (result.started()) return true;
        render();
        return false;
    }

    public Inventory inventory() { return inventory; }
    public String message() { return message; }

    private void render() {
        if (inventory == null) return;
        inventory.clear();
        inventory.setItem(IRON_SLOT, item(Material.IRON_INGOT, "Iron input", List.of("Required: 1")));
        inventory.setItem(COAL_SLOT, item(Material.COAL, "Coal fuel", List.of("Required: 1")));
        inventory.setItem(OUTPUT_SLOT, item(Material.IRON_NUGGET, "Alloy output", List.of("Preview")));
        inventory.setItem(START_SLOT, item(Material.LIME_CONCRETE, "Start process", List.of("Click to begin")));
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
