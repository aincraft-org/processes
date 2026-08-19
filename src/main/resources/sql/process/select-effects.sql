SELECT effect_id, effect_type, state
FROM {schema}.effect_ledger
WHERE instance_id = ?
ORDER BY effect_index
