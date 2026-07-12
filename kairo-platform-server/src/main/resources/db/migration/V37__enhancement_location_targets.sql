-- V1.3 §3.5: persist enhancement location + call-site selector on rule targets.
--
-- The rule target JSON (matcher_json) already carries the method descriptor and class id. V1.3 adds
-- the authoritative EnhancementLocation and, for call-site locations, the CallSiteSelector so the
-- platform can describe constructors, FINALLY and intra-method call sites — not just the three
-- legacy method phases.
--
-- Legacy V1.0/V1.2 rules keep location NULL: the read side derives METHOD_ENTER / METHOD_RETURN /
-- METHOD_THROW from the legacy phase recorded in the script blob, so existing rules run unchanged
-- with no data migration. location and call_site_selector_json are mirrored into matcher_json as
-- well (the "rule target JSON" the roadmap calls out), while the explicit columns support indexing
-- and the rule-ledger read path.

alter table rule_target add column location varchar(32);
alter table rule_target add column call_site_selector_json text;

create index idx_rule_target_location on rule_target(location);

alter table rule_target add constraint ck_rule_target_location check (
    location is null or location in (
        'METHOD_ENTER', 'METHOD_RETURN', 'METHOD_THROW', 'METHOD_FINALLY',
        'CONSTRUCTOR_AFTER_SUPER', 'CONSTRUCTOR_RETURN', 'CONSTRUCTOR_THROW',
        'CALL_BEFORE', 'CALL_RETURN', 'CALL_THROW'
    )
);

-- A call-site location must carry a selector; a selector may only attach to a call-site location.
alter table rule_target add constraint ck_rule_target_call_site_selector check (
    (location not in ('CALL_BEFORE', 'CALL_RETURN', 'CALL_THROW') or call_site_selector_json is not null)
    and (location in ('CALL_BEFORE', 'CALL_RETURN', 'CALL_THROW') or call_site_selector_json is null)
);
