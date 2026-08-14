package dev.craftingmanager.example;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ExampleGuiListener implements Listener {
    private final Map<UUID, ExampleProcessGui> sessions = new HashMap<>();

    public void track(Player player, ExampleProcessGui gui) {
        sessions.put(player.getUniqueId(), gui);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ExampleProcessGui gui = sessions.get(player.getUniqueId());
        if (gui == null || !gui.owns(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        if (event.getRawSlot() < event.getView().getTopInventory().getSize() && gui.handleClick(event.getRawSlot())) {
            player.closeInventory();
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            ExampleProcessGui gui = sessions.get(player.getUniqueId());
            if (gui != null && gui.owns(event.getInventory())) sessions.remove(player.getUniqueId());
        }
    }

    int sessionCount() { return sessions.size(); }
}
