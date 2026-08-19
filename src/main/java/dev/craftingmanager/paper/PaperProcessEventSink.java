package dev.craftingmanager.paper;

import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.ProcessEventSink;
import dev.craftingmanager.api.ProcessUsage;
import dev.craftingmanager.api.event.PreProcessEvent;
import dev.craftingmanager.api.event.ProcessFinishedEvent;
import dev.craftingmanager.api.event.ProcessStartedEvent;
import org.bukkit.Bukkit;

/** Fires Bukkit process-usage events and applies optional protection access. */
public final class PaperProcessEventSink implements ProcessEventSink {
    private final ProtectionAccess access;

    public PaperProcessEventSink() {
        this(ProtectionAccess.allowAll());
    }

    public PaperProcessEventSink(ProtectionAccess access) {
        this.access = access == null ? ProtectionAccess.allowAll() : access;
    }

    @Override public boolean emitStarting(ProcessUsage usage) {
        if (!access.canInteract(usage.block(), usage.owner())) return false;
        PreProcessEvent event = new PreProcessEvent(usage.block(), usage.processId(), usage.owner());
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    @Override public void emitStarted(ProcessUsage usage) {
        Bukkit.getPluginManager().callEvent(new ProcessStartedEvent(
                usage.instanceId(), usage.block(), usage.processId(), usage.owner()));
    }

    @Override public void emitFinished(ProcessUsage usage, ProcessState state) {
        Bukkit.getPluginManager().callEvent(new ProcessFinishedEvent(
                usage.instanceId(), usage.block(), usage.processId(), usage.owner(), state));
    }
}
