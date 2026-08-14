package dev.craftingmanager;

import dev.craftingmanager.api.Domain.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BlockIdentityTest {
    @Test void preservesWorldAndNegativeCoordinates() {
        UUID world = UUID.randomUUID();
        assertEquals(new BlockKey(world, -10, 64, -20), new BlockKey(world, -10, 64, -20));
        assertNotEquals(new BlockKey(UUID.randomUUID(), -10, 64, -20), new BlockKey(world, -10, 64, -20));
    }
}
