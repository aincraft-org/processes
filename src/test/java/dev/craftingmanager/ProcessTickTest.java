package dev.craftingmanager;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.CompletionEffect;
import dev.craftingmanager.api.Domain.ProcessDefinition;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.Domain.ProcessStep;
import dev.craftingmanager.api.Domain.UnregisterPolicy;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProcessTickTest {
    @Test void durationTicksMustElapseBeforeTheStepCompletes() {
        RuntimeEngine engine = engine(new ProcessStep("heat", "Heat", 2));
        CraftingManagerApi.ProcessStartResult started = start(engine);
        assertEquals(ProcessState.RUNNING, engine.advance(started.instanceId()).toCompletableFuture().join());
        assertEquals(ProcessState.COMPLETED, engine.advance(started.instanceId()).toCompletableFuture().join());
    }

    @Test void lastStepCompletionAppliesEffectsOnTheSameTick() {
        AtomicInteger executions = new AtomicInteger();
        RuntimeEngine engine = engine(executions, new ProcessStep("work", "Work", 1));
        CraftingManagerApi.ProcessStartResult started = start(engine);
        assertEquals(0, executions.get());
        assertEquals(ProcessState.COMPLETED, engine.advance(started.instanceId()).toCompletableFuture().join());
        assertEquals(1, executions.get());
    }

    @Test void zeroDurationStepCompletesOnTheFirstTickThatEntersIt() {
        RuntimeEngine engine = engine(new ProcessStep("instant", "Instant", 0));
        CraftingManagerApi.ProcessStartResult started = start(engine);
        assertEquals(ProcessState.RUNNING, engine.state(started.instanceId()).orElseThrow());
        assertEquals(ProcessState.COMPLETED, engine.advance(started.instanceId()).toCompletableFuture().join());
    }

    @Test void emptyStepListCompletesOnTheFirstTick() {
        RuntimeEngine engine = engine();
        CraftingManagerApi.ProcessStartResult started = start(engine);
        assertEquals(ProcessState.COMPLETED, engine.advance(started.instanceId()).toCompletableFuture().join());
    }

    @Test void twoStepsRequireEachDurationInOrder() {
        RuntimeEngine engine = engine(
                new ProcessStep("heat", "Heat", 2),
                new ProcessStep("smelt", "Smelt", 1));
        CraftingManagerApi.ProcessStartResult started = start(engine);
        assertEquals(ProcessState.RUNNING, engine.advance(started.instanceId()).toCompletableFuture().join());
        assertEquals(ProcessState.RUNNING, engine.advance(started.instanceId()).toCompletableFuture().join());
        assertEquals(ProcessState.COMPLETED, engine.advance(started.instanceId()).toCompletableFuture().join());
    }

    @Test void tickAdvancesEveryLoadedRunningInstance() {
        RuntimeEngine engine = engine(new ProcessStep("work", "Work", 1));
        UUID first = start(engine, new BlockKey(UUID.randomUUID(), 1, 64, 1)).instanceId();
        UUID second = start(engine, new BlockKey(UUID.randomUUID(), 2, 64, 2)).instanceId();
        engine.tick();
        assertEquals(ProcessState.COMPLETED, engine.state(first).orElseThrow());
        assertEquals(ProcessState.COMPLETED, engine.state(second).orElseThrow());
    }

    @Test void startMarksTheHostChunkLoadedSoTheProcessCanTick() {
        RuntimeEngine engine = engine(new ProcessStep("work", "Work", 1));
        CraftingManagerApi.ProcessStartResult started = start(engine);
        engine.tick();
        assertEquals(ProcessState.COMPLETED, engine.state(started.instanceId()).orElseThrow());
    }

    @Test void ticksOnlyWhileTheHostChunkIsLoaded() {
        RuntimeEngine engine = engine(new ProcessStep("heat", "Heat", 2));
        BlockKey block = new BlockKey(UUID.randomUUID(), 19, 64, 35);
        CraftingManagerApi.ProcessStartResult started = start(engine, block);
        engine.unloadChunk(block.worldId(), Math.floorDiv(block.x(), 16), Math.floorDiv(block.z(), 16));
        engine.tick();
        engine.tick();
        engine.tick();
        assertEquals(ProcessState.RUNNING, engine.state(started.instanceId()).orElseThrow());
        engine.loadChunk(block.worldId(), Math.floorDiv(block.x(), 16), Math.floorDiv(block.z(), 16));
        engine.tick();
        assertEquals(ProcessState.RUNNING, engine.state(started.instanceId()).orElseThrow());
        engine.tick();
        assertEquals(ProcessState.COMPLETED, engine.state(started.instanceId()).orElseThrow());
    }

    @Test void onlyTheLoadedChunkGainsProgress() {
        RuntimeEngine engine = engine(new ProcessStep("heat", "Heat", 1));
        BlockKey loaded = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        BlockKey unloaded = new BlockKey(loaded.worldId(), 32, 64, 0);
        UUID running = start(engine, loaded).instanceId();
        UUID paused = start(engine, unloaded).instanceId();
        engine.unloadChunk(unloaded.worldId(), Math.floorDiv(unloaded.x(), 16), Math.floorDiv(unloaded.z(), 16));
        engine.tick();
        assertEquals(ProcessState.COMPLETED, engine.state(running).orElseThrow());
        assertEquals(ProcessState.RUNNING, engine.state(paused).orElseThrow());
    }

    @Test void parkedInstancesDoNotGainProgress() {
        AtomicInteger executions = new AtomicInteger();
        RuntimeEngine engine = new RuntimeEngine();
        var handle = engine.registerEffectHandler(handler(executions, false), UnregisterPolicy.FAIL_ACTIVE_PROCESSES);
        engine.registerProcess(new ProcessDefinition(
                "forge", List.of(), List.of(new ProcessStep("work", "Work", 1)),
                List.of((CompletionEffect) () -> "item-output")));
        CraftingManagerApi.ProcessStartResult started = start(engine);
        handle.close();
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.state(started.instanceId()).orElseThrow());
        engine.registerEffectHandler(handler(executions, false));
        engine.tick();
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.state(started.instanceId()).orElseThrow());
        assertEquals(0, executions.get());
    }

    private static CraftingManagerApi.ProcessStartResult start(RuntimeEngine engine) {
        return start(engine, new BlockKey(UUID.randomUUID(), 0, 64, 0));
    }

    private static CraftingManagerApi.ProcessStartResult start(RuntimeEngine engine, BlockKey block) {
        CraftingManagerApi.ProcessStartResult started = engine.start(block, "forge", UUID.randomUUID());
        assertTrue(started.started(), started.reason());
        return started;
    }

    private static RuntimeEngine engine(ProcessStep... steps) {
        return engine(new AtomicInteger(), steps);
    }

    private static RuntimeEngine engine(AtomicInteger executions, ProcessStep... steps) {
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(handler(executions, false));
        engine.registerProcess(new ProcessDefinition(
                "forge", List.of(), List.of(steps), List.of((CompletionEffect) () -> "item-output")));
        return engine;
    }

    private static EffectHandler<CompletionEffect> handler(AtomicInteger executions, boolean fail) {
        return new EffectHandler<>() {
            @Override public String type() { return "item-output"; }
            @Override public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            @Override public void execute(CompletionEffect effect, String effectId) {
                executions.incrementAndGet();
                if (fail) throw new IllegalStateException("provider failure");
            }
        };
    }
}
