package dev.craftingmanager.persistence;

import dev.craftingmanager.api.Domain.BlockKey;

import java.util.List;
import java.util.UUID;

public interface ProcessStore {
    void save(ProcessInstanceRecord record);
    void delete(UUID instanceId);
    List<ProcessInstanceRecord> loadAll();
    void saveBlock(BlockKey key, String definitionId);
    void removeBlock(BlockKey key);
    List<FunctionalBlockRecord> loadBlocks();

    static ProcessStore none() {
        return new ProcessStore() {
            @Override public void save(ProcessInstanceRecord record) {}
            @Override public void delete(UUID instanceId) {}
            @Override public List<ProcessInstanceRecord> loadAll() { return List.of(); }
            @Override public void saveBlock(BlockKey key, String definitionId) {}
            @Override public void removeBlock(BlockKey key) {}
            @Override public List<FunctionalBlockRecord> loadBlocks() { return List.of(); }
        };
    }
}
