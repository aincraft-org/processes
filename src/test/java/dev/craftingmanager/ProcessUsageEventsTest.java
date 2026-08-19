package dev.craftingmanager;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.CompletionEffect;
import dev.craftingmanager.api.Domain.ProcessDefinition;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.Domain.ProcessStep;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.api.ProcessEventSink;
import dev.craftingmanager.api.ProcessUsage;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProcessUsageEventsTest {
    @Test void startEmitsStartingThenStartedWithOwnerAndProcess() {
        RecordingSink sink = new RecordingSink();
        RuntimeEngine engine = engine(sink);
        UUID owner = UUID.randomUUID();
        BlockKey block = new BlockKey(UUID.randomUUID(), 1, 64, 1);
        CraftingManagerApi.ProcessStartResult result = engine.start(block, "forge", owner);
        assertTrue(result.started(), result.reason());
        assertEquals(1, sink.starting.size());
        assertEquals(1, sink.started.size());
        ProcessUsage started = sink.started.getFirst();
        assertEquals(owner, started.owner());
        assertEquals(block, started.block());
        assertEquals("forge", started.processId());
        assertEquals(result.instanceId(), started.instanceId());
        assertNull(sink.starting.getFirst().instanceId());
    }

    @Test void cancelledStartingEventRejectsStartAndConsumesNothing() {
        RecordingSink sink = new RecordingSink();
        sink.cancelStarting = true;
        RuntimeEngine engine = engine(sink);
        UUID owner = UUID.randomUUID();
        CraftingManagerApi.ProcessStartResult result = engine.start(
                new BlockKey(UUID.randomUUID(), 2, 64, 2), "forge", owner);
        assertFalse(result.started());
        assertEquals("cancelled", result.reason());
        assertEquals(1, sink.starting.size());
        assertTrue(sink.started.isEmpty());
        assertEquals(owner, sink.starting.getFirst().owner());
    }

    @Test void completingEmitsFinishedWithOwnerAndCompletedState() {
        RecordingSink sink = new RecordingSink();
        RuntimeEngine engine = engine(sink);
        UUID owner = UUID.randomUUID();
        CraftingManagerApi.ProcessStartResult result = engine.start(
                new BlockKey(UUID.randomUUID(), 3, 64, 3), "forge", owner);
        assertTrue(result.started(), result.reason());
        assertEquals(ProcessState.COMPLETED, engine.advance(result.instanceId()).toCompletableFuture().join());
        assertEquals(1, sink.finished.size());
        ProcessUsage finished = sink.finished.getFirst();
        assertEquals(owner, finished.owner());
        assertEquals("forge", finished.processId());
        assertEquals(result.instanceId(), finished.instanceId());
        assertEquals(ProcessState.COMPLETED, sink.finishedStates.getFirst());
    }

    private static RuntimeEngine engine(ProcessEventSink sink) {
        RuntimeEngine engine = new RuntimeEngine(dev.craftingmanager.persistence.ProcessStore.none(), sink);
        engine.registerEffectHandler(new EffectHandler<CompletionEffect>() {
            @Override public String type() { return "item-output"; }
            @Override public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            @Override public void execute(CompletionEffect effect, String effectId) {}
        });
        engine.registerProcess(new ProcessDefinition(
                "forge", List.of(), List.of(), List.of((CompletionEffect) () -> "item-output")));
        return engine;
    }

    private static final class RecordingSink implements ProcessEventSink {
        final List<ProcessUsage> starting = new ArrayList<>();
        final List<ProcessUsage> started = new ArrayList<>();
        final List<ProcessUsage> finished = new ArrayList<>();
        final List<ProcessState> finishedStates = new ArrayList<>();
        boolean cancelStarting;

        @Override public boolean emitStarting(ProcessUsage usage) {
            starting.add(usage);
            return !cancelStarting;
        }

        @Override public void emitStarted(ProcessUsage usage) {
            started.add(usage);
        }

        @Override public void emitFinished(ProcessUsage usage, ProcessState state) {
            finished.add(usage);
            finishedStates.add(state);
        }
    }
}
