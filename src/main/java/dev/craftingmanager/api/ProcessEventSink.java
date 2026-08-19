package dev.craftingmanager.api;

import static dev.craftingmanager.api.Domain.ProcessState;

/** Receives process usage events. Paper fires Bukkit events; tests record. */
public interface ProcessEventSink {
    /** @return false to reject the start */
    boolean emitStarting(ProcessUsage usage);
    void emitStarted(ProcessUsage usage);
    void emitFinished(ProcessUsage usage, ProcessState state);

    static ProcessEventSink noop() {
        return new ProcessEventSink() {
            @Override public boolean emitStarting(ProcessUsage usage) { return true; }
            @Override public void emitStarted(ProcessUsage usage) {}
            @Override public void emitFinished(ProcessUsage usage, ProcessState state) {}
        };
    }
}
