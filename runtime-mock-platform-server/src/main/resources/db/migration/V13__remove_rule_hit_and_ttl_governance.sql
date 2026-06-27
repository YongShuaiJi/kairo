update rule_version
   set governance_json = '{}'
 where governance_json::jsonb ? 'maxHits'
    or governance_json::jsonb ? 'ttlSeconds';
