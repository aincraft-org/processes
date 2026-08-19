SELECT source, slot, material, amount, metadata, input_id, policy
FROM {schema}.reservations
WHERE instance_id = ?
ORDER BY claim_index
