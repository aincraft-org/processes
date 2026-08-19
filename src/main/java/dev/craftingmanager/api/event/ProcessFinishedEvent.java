package dev.craftingmanager.api.event;

import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ProcessState;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import java.util.UUID;

/** Fired when a process instance reaches a terminal or parked state. */
public final class ProcessFinishedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID instanceId;
    private final BlockKey block;
    private final String processId;
    private final UUID owner;
    private final ProcessState state;

    public ProcessFinishedEvent(UUID instanceId, BlockKey block, String processId, UUID owner, ProcessState state) {
        this.instanceId = instanceId;
        this.block = block;
        this.processId = processId;
        this.owner = owner;
        this.state = state;
    }

    public UUID instanceId() { return instanceId; }
    public BlockKey block() { return block; }
    public String processId() { return processId; }
    public UUID owner() { return owner; }
    public ProcessState state() { return state; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
