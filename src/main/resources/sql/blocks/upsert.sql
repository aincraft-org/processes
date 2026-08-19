INSERT INTO {schema}.functional_blocks(world_id, x, y, z, definition_id) VALUES (?,?,?,?,?)
ON CONFLICT(world_id, x, y, z) DO UPDATE SET definition_id=excluded.definition_id
