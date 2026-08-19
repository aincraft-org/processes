package dev.craftingmanager;

import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.event.PreProcessEvent;
import dev.craftingmanager.api.event.ProcessEvent;
import dev.craftingmanager.api.event.ProcessFinishedEvent;
import dev.craftingmanager.api.event.ProcessStartedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProcessEventTest {
    @Test void implementorsCanListenToProcessEventForEveryPhase() {
        UUID owner = UUID.randomUUID();
        BlockKey block = new BlockKey(UUID.randomUUID(), 1, 64, 1);
        UUID instance = UUID.randomUUID();
        PreProcessEvent pre = new PreProcessEvent(block, "forge", owner);
        ProcessStartedEvent started = new ProcessStartedEvent(instance, block, "forge", owner);
        ProcessFinishedEvent finished = new ProcessFinishedEvent(
                instance, block, "forge", owner, ProcessState.COMPLETED);

        assertInstanceOf(ProcessEvent.class, pre);
        assertInstanceOf(ProcessEvent.class, started);
        assertInstanceOf(ProcessEvent.class, finished);
        assertEquals(owner, ((ProcessEvent) pre).owner());
        assertEquals("forge", started.processId());
        assertEquals(block, finished.block());
        assertEquals(instance, started.instanceId());
        assertNull(pre.instanceId());
        pre.setCancelled(true);
        assertTrue(pre.isCancelled());
    }
}
