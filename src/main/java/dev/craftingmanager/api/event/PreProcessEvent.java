package dev.craftingmanager.api.event;

import dev.craftingmanager.api.Domain.BlockKey;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired before a process consumes inputs. Cancel to deny the start. */
public final class PreProcessEvent extends ProcessEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private boolean cancelled;
    private String cancelReason = "cancelled";

    public PreProcessEvent(BlockKey block, String processId, UUID owner) {
        super(null, block, processId, owner);
    }

    public String cancelReason() { return cancelReason; }

    public void setCancelReason(String cancelReason) {
        if (cancelReason != null && !cancelReason.isBlank()) this.cancelReason = cancelReason;
    }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
