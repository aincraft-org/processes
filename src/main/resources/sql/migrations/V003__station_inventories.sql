CREATE TABLE IF NOT EXISTS {schema}.station_inventories (
    world_id TEXT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    slot_id TEXT NOT NULL,
    material TEXT NOT NULL,
    amount INTEGER NOT NULL,
    metadata TEXT NOT NULL,
    PRIMARY KEY (world_id, x, y, z, slot_id)
);
