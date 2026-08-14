package dev.craftingmanager;

import dev.craftingmanager.api.ItemSnapshot;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class ItemSnapshotTest {
    @Test void copiesMetadata() {
        HashMap<String, String> metadata = new HashMap<>();
        metadata.put("model", "1");
        ItemSnapshot snapshot = new ItemSnapshot("IRON_INGOT", 2, metadata);
        metadata.put("model", "2");
        assertEquals("1", snapshot.metadata().get("model"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.metadata().put("x", "y"));
    }
}
