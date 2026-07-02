update rule_version
   set governance_json = '{}'
 where governance_json like '%"maxHits"%'
    or governance_json like '%"ttlSeconds"%';
