package dev.craftingmanager.example;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.ItemSnapshot;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
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
    private final Screen screen;
    private Inventory inventory;
    private String message;

    public ExampleProcessGui(CraftingManagerApi api, BlockKey block, UUID owner, String processId) {
        this.api = Objects.requireNonNull(api);
        this.block = Objects.requireNonNull(block);
        this.owner = Objects.requireNonNull(owner);
        this.processId = requireText(processId, "processId");
        this.screen = screenFor(this.processId);
    }

    public String title() {
        return screen.title();
    }

    public void open(Player player) {
        Objects.requireNonNull(player);
        if (!player.getUniqueId().equals(owner)) throw new IllegalArgumentException("player does not own this GUI");
        inventory = Bukkit.createInventory(null, SIZE, screen.title());
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

    /** CustomPack catalog id for a GUI slot, or null for decorative slots. */
    public static String itemModel(int slot) {
        return switch (slot) {
            case IRON_SLOT -> "craftingmanager:iron_input";
            case COAL_SLOT -> "craftingmanager:coal_fuel";
            case OUTPUT_SLOT -> "craftingmanager:alloy_output";
            case START_SLOT -> "craftingmanager:start_process";
            default -> null;
        };
    }

    private void render() {
        if (inventory == null) return;
        inventory.clear();
        for (Slot slot : screen.slots()) {
            ItemSnapshot stored = slot.slotId() == null ? null : api.slot(block, slot.slotId()).orElse(null);
            if (stored != null) {
                inventory.setItem(slot.index(), item(Material.valueOf(stored.material()), slot.name(),
                        List.of("Stored: " + stored.amount()), slot.itemModel()));
            } else {
                inventory.setItem(slot.index(), item(slot.material(), slot.name(), slot.lore(), slot.itemModel()));
            }
        }
        inventory.setItem(START_SLOT, item(Material.LIME_CONCRETE, "Start process", List.of("Click to begin"),
                "craftingmanager:start_process"));
    }

    private static ItemStack item(Material material, String name, List<String> lore, String model) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        stack.setItemMeta(meta);
        if (model != null) {
            stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(model));
        }
        return stack;
    }

    private static Screen screenFor(String processId) {
        if (ExtraProcessProvider.POLISH_PROCESS_ID.equals(processId)) {
            return new Screen("Gem Polisher", List.of(
                    new Slot(10, Material.AMETHYST_SHARD, "Rough gem", List.of("Required: 1"), null, "rough"),
                    new Slot(12, Material.IRON_PICKAXE, "Polishing tool", List.of("Returned on success"), null, "tool"),
                    new Slot(14, Material.QUARTZ, "Polished gem", List.of("Preview"), null, "gem")));
        }
        if (ExtraProcessProvider.MIX_PROCESS_ID.equals(processId)) {
            return new Screen("Tonic Mixer", List.of(
                    new Slot(10, Material.REDSTONE, "Base", List.of("Required: 1"), null, "base"),
                    new Slot(11, Material.GLOWSTONE_DUST, "Reagent", List.of("Required: 1"), null, "reagent"),
                    new Slot(12, Material.BLAZE_POWDER, "Catalyst", List.of("Returned always"), null, "catalyst"),
                    new Slot(13, Material.SUGAR, "Additive", List.of("Optional"), null, "sweetener"),
                    new Slot(14, Material.GLOW_INK_SAC, "Mixed tonic", List.of("Preview"), null, "tonic")));
        }
        return new Screen(TITLE, List.of(
                new Slot(IRON_SLOT, Material.IRON_INGOT, "Iron input", List.of("Required: 1"), itemModel(IRON_SLOT), "iron"),
                new Slot(COAL_SLOT, Material.COAL, "Coal fuel", List.of("Required: 1"), itemModel(COAL_SLOT), "fuel"),
                new Slot(OUTPUT_SLOT, Material.IRON_NUGGET, "Alloy output", List.of("Preview"), itemModel(OUTPUT_SLOT), "alloy")));
    }

    private record Screen(String title, List<Slot> slots) {}
    private record Slot(int index, Material material, String name, List<String> lore, String itemModel, String slotId) {}

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
