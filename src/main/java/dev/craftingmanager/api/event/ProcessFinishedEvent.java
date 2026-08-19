package dev.craftingmanager.api.event;

import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ProcessState;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired when a process instance reaches a terminal or parked state. */
public final class ProcessFinishedEvent extends ProcessEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final ProcessState state;

    public ProcessFinishedEvent(UUID instanceId, BlockKey block, String processId, UUID owner, ProcessState state) {
        super(instanceId, block, processId, owner);
        this.state = state;
    }

    public ProcessState state() { return state; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
