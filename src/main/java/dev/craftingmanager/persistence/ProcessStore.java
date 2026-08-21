package dev.craftingmanager.persistence;

import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.ItemSnapshot;

import java.util.List;
import java.util.UUID;

public interface ProcessStore {
    void save(ProcessInstanceRecord record);
    void delete(UUID instanceId);
    List<ProcessInstanceRecord> loadAll();
    void saveBlock(BlockKey key, String definitionId);
    void removeBlock(BlockKey key);
    List<FunctionalBlockRecord> loadBlocks();
    void saveSlot(BlockKey key, String slotId, ItemSnapshot item);
    void removeSlot(BlockKey key, String slotId);
    void removeSlots(BlockKey key);
    List<StationSlotRecord> loadSlots();
    void flush();

    static ProcessStore none() {
        return new ProcessStore() {
            @Override public void save(ProcessInstanceRecord record) {}
            @Override public void delete(UUID instanceId) {}
            @Override public List<ProcessInstanceRecord> loadAll() { return List.of(); }
            @Override public void saveBlock(BlockKey key, String definitionId) {}
            @Override public void removeBlock(BlockKey key) {}
            @Override public List<FunctionalBlockRecord> loadBlocks() { return List.of(); }
            @Override public void saveSlot(BlockKey key, String slotId, ItemSnapshot item) {}
            @Override public void removeSlot(BlockKey key, String slotId) {}
            @Override public void removeSlots(BlockKey key) {}
            @Override public List<StationSlotRecord> loadSlots() { return List.of(); }
            @Override public void flush() {}
        };
    }
}
