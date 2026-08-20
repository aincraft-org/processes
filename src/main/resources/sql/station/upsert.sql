INSERT INTO {schema}.station_inventories(world_id, x, y, z, slot_id, material, amount, metadata)
VALUES (?,?,?,?,?,?,?,?)
ON CONFLICT(world_id, x, y, z, slot_id) DO UPDATE SET
    material=excluded.material, amount=excluded.amount, metadata=excluded.metadata
