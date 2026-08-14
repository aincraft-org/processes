package dev.craftingmanager;

import dev.craftingmanager.api.Domain.*;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EffectLedgerTest {
    private static final CompletionEffect FIRST = () -> "first";
    private static final CompletionEffect SECOND = () -> "second";

    @Test void ambiguousFailurePreservesAppliedEffects() {
        RuntimeEngine engine = new RuntimeEngine();
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        engine.registerEffectHandler(handler("first", firstCalls, false));
        engine.registerEffectHandler(handler("second", secondCalls, true));
        engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(), List.of(FIRST, SECOND)));

        var result = engine.start(new BlockKey(UUID.randomUUID(), 0, 0, 0), "job", UUID.randomUUID());
        assertTrue(result.started());
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.advance(result.instanceId()).toCompletableFuture().join());
        assertEquals(List.of(EffectExecutionState.APPLIED, EffectExecutionState.UNKNOWN),
                engine.ledger(result.instanceId()).orElseThrow().stream().map(EffectExecution::state).toList());
        assertEquals(1, firstCalls.get());
        assertEquals(1, secondCalls.get());
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.advance(result.instanceId()).toCompletableFuture().join());
        assertEquals(1, firstCalls.get());
        assertEquals(1, secondCalls.get());
    }

    @Test void defaultHandlerUnregisterRejectsActiveProcess() {
        RuntimeEngine engine = new RuntimeEngine();
        var registration = engine.registerEffectHandler(handler("first", new AtomicInteger(), false));
        engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(new ProcessStep("wait", "Wait", 1)), List.of(FIRST)));
        var result = engine.start(new BlockKey(UUID.randomUUID(), 0, 0, 0), "job", UUID.randomUUID());
        assertTrue(result.started());
        assertThrows(IllegalStateException.class, registration::close);
        assertEquals(ProcessState.RUNNING, engine.state(result.instanceId()).orElseThrow());
    }

    private static EffectHandler<CompletionEffect> handler(String type, AtomicInteger calls, boolean fail) {
        return new EffectHandler<>() {
            public String type() { return type; }
            public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            public void execute(CompletionEffect effect, String effectId) {
                calls.incrementAndGet();
                if (fail) throw new IllegalStateException("provider failure");
            }
        };
    }
}
