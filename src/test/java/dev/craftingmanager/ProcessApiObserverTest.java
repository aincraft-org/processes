package dev.craftingmanager;

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

class ProcessApiObserverTest {
    @Test void registerSinkReceivesStartedAndFinished() {
        RecordingSink sink = new RecordingSink();
        RuntimeEngine engine = new RuntimeEngine();
        try (var handle = engine.registerProcessEventSink(sink)) {
            engine.registerEffectHandler(new EffectHandler<CompletionEffect>() {
                public String type() { return "item-output"; }
                public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
                public void execute(CompletionEffect effect, String effectId) {}
            });
            engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(new ProcessStep("one", "One", 1)), List.of((CompletionEffect) () -> "item-output")));
            var started = engine.start(new BlockKey(UUID.randomUUID(), 0, 64, 0), "job", UUID.randomUUID());
            assertTrue(started.started());
            engine.advance(started.instanceId()).toCompletableFuture().join();
        }
        assertEquals(1, sink.started.size());
        assertEquals(1, sink.finished.size());
        assertEquals(ProcessState.COMPLETED, sink.finishedStates.getFirst());
    }

    @Test void closingHandleStopsEvents() {
        RecordingSink sink = new RecordingSink();
        RuntimeEngine engine = new RuntimeEngine();
        var handle = engine.registerProcessEventSink(sink);
        handle.close();
        engine.registerEffectHandler(new EffectHandler<CompletionEffect>() {
            public String type() { return "item-output"; }
            public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            public void execute(CompletionEffect effect, String effectId) {}
        });
        engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(new ProcessStep("one", "One", 1)), List.of((CompletionEffect) () -> "item-output")));
        var started = engine.start(new BlockKey(UUID.randomUUID(), 0, 64, 0), "job", UUID.randomUUID());
        assertTrue(started.started());

        assertEquals(0, sink.started.size());
    }

    private static final class RecordingSink implements ProcessEventSink {
        final List<ProcessUsage> started = new ArrayList<>();
        final List<ProcessUsage> starting = new ArrayList<>();
        final List<ProcessUsage> finished = new ArrayList<>();
        final List<ProcessState> finishedStates = new ArrayList<>();

        @Override public boolean emitStarting(ProcessUsage usage) { starting.add(usage); return true; }
        @Override public void emitStarted(ProcessUsage usage) { started.add(usage); }
        @Override public void emitFinished(ProcessUsage usage, ProcessState state) { finished.add(usage); finishedStates.add(state); }
    }
}
