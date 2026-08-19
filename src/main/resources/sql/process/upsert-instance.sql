INSERT INTO {schema}.process_instances(
    instance_id, world_id, x, y, z, process_id, owner, revision, step, state, reservation_state)
VALUES (?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT(instance_id) DO UPDATE SET
    world_id=excluded.world_id, x=excluded.x, y=excluded.y, z=excluded.z,
    process_id=excluded.process_id, owner=excluded.owner, revision=excluded.revision,
    step=excluded.step, state=excluded.state, reservation_state=excluded.reservation_state
