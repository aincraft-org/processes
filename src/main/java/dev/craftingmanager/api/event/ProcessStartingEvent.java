package dev.craftingmanager.api.event;

import dev.craftingmanager.api.Domain.BlockKey;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import java.util.UUID;

/** Fired before a process consumes inputs. Cancel to deny the start. */
public final class ProcessStartingEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final BlockKey block;
    private final String processId;
    private final UUID owner;
    private boolean cancelled;
    private String cancelReason = "cancelled";

    public ProcessStartingEvent(BlockKey block, String processId, UUID owner) {
        this.block = block;
        this.processId = processId;
        this.owner = owner;
    }

    public BlockKey block() { return block; }
    public String processId() { return processId; }
    public UUID owner() { return owner; }
    public String cancelReason() { return cancelReason; }

    public void setCancelReason(String cancelReason) {
        if (cancelReason != null && !cancelReason.isBlank()) this.cancelReason = cancelReason;
    }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
