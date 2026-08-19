package dev.craftingmanager.api.event;

import dev.craftingmanager.api.Domain.BlockKey;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired after a process instance is created and inputs are reserved. */
public final class ProcessStartedEvent extends ProcessEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    public ProcessStartedEvent(UUID instanceId, BlockKey block, String processId, UUID owner) {
        super(instanceId, block, processId, owner);
    }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
