SELECT instance_id, world_id, x, y, z, process_id, owner, revision, step, step_ticks, state, reservation_state, parked_reason
FROM {schema}.process_instances
