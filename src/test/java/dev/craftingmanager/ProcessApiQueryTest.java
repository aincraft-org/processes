package dev.craftingmanager;

import dev.craftingmanager.api.Domain.*;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProcessApiQueryTest {
    @Test void activeInstanceReturnsSnapshotForRunningProcess() {
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(new EffectHandler<CompletionEffect>() {
            public String type() { return "item-output"; }
            public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            public void execute(CompletionEffect effect, String effectId) {}
        });
        engine.registerProcess(new ProcessDefinition("job", List.of(),
                List.of(new ProcessStep("one", "One", 1), new ProcessStep("two", "Two", 1)),
                List.of((CompletionEffect) () -> "item-output")));
        BlockKey block = new BlockKey(UUID.randomUUID(), 1, 64, 1);
        var started = engine.start(block, "job", UUID.randomUUID());
        assertTrue(started.started());
        engine.advance(started.instanceId()).toCompletableFuture().join();

        var snapshot = engine.activeInstance(block).orElseThrow();

        assertEquals(started.instanceId(), snapshot.instanceId());
        assertEquals("job", snapshot.processId());
        assertEquals(ProcessState.RUNNING, snapshot.state());
        assertEquals(1, snapshot.stepIndex());
        assertEquals(0, snapshot.stepTicks());
        assertEquals(1, snapshot.stepDurationTicks());
    }

    @Test void activeInstanceReturnsEmptyForFreeBlock() {
        RuntimeEngine engine = new RuntimeEngine();
        assertTrue(engine.activeInstance(new BlockKey(UUID.randomUUID(), 0, 64, 0)).isEmpty());
    }

    @Test void activeInstanceReturnsParkedSnapshotAndBlockRemainsBusy() {
        RuntimeEngine engine = new RuntimeEngine();
        AtomicInteger calls = new AtomicInteger();
        engine.registerEffectHandler(handler("first", calls, false));
        engine.registerEffectHandler(handler("second", calls, true));
        engine.registerProcess(new ProcessDefinition("job", List.of(),
                List.of(new ProcessStep("one", "One", 1)),
                List.of(() -> "first", () -> "second")));
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var started = engine.start(block, "job", UUID.randomUUID());
        assertTrue(started.started());
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.advance(started.instanceId()).toCompletableFuture().join());

        var snapshot = engine.activeInstance(block).orElseThrow();

        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, snapshot.state());
        assertFalse(engine.start(block, "job", UUID.randomUUID()).started());
    }

    private static EffectHandler<CompletionEffect> handler(String type, AtomicInteger calls, boolean fail) {
        return new EffectHandler<>() {
            @Override public String type() { return type; }
            @Override public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            @Override public void execute(CompletionEffect effect, String effectId) {
                calls.incrementAndGet();
                if (fail) throw new IllegalStateException("provider failure");
            }
        };
    }
}
