CREATE TABLE IF NOT EXISTS {schema}.schema_version (
    schema TEXT PRIMARY KEY,
    version INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS {schema}.process_instances (
    instance_id TEXT PRIMARY KEY,
    world_id TEXT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    process_id TEXT NOT NULL,
    owner TEXT NOT NULL,
    revision INTEGER NOT NULL,
    step INTEGER NOT NULL,
    state TEXT NOT NULL,
    reservation_state TEXT
);

CREATE TABLE IF NOT EXISTS {schema}.reservations (
    instance_id TEXT NOT NULL,
    claim_index INTEGER NOT NULL,
    source TEXT NOT NULL,
    slot INTEGER NOT NULL,
    material TEXT NOT NULL,
    amount INTEGER NOT NULL,
    metadata TEXT NOT NULL,
    input_id TEXT NOT NULL,
    policy TEXT NOT NULL,
    PRIMARY KEY (instance_id, claim_index)
);

CREATE TABLE IF NOT EXISTS {schema}.effect_ledger (
    instance_id TEXT NOT NULL,
    effect_index INTEGER NOT NULL,
    effect_id TEXT NOT NULL,
    effect_type TEXT NOT NULL,
    state TEXT NOT NULL,
    PRIMARY KEY (instance_id, effect_index)
);

CREATE TABLE IF NOT EXISTS {schema}.functional_blocks (
    world_id TEXT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    definition_id TEXT NOT NULL,
    PRIMARY KEY (world_id, x, y, z)
);
