\set ON_ERROR_STOP on
\if :{?apply}
\else
\set apply false
\endif

BEGIN;
SELECT :'apply'::boolean AS apply_requested;
LOCK TABLE raw_record IN EXCLUSIVE MODE;
LOCK TABLE users, organization IN SHARE MODE;

CREATE TEMP TABLE raw_record_owner_mapping (
    raw_record_id bigint NOT NULL,
    legacy_kakao_id text NOT NULL,
    organization_id bigint NOT NULL
) ON COMMIT DROP;
\copy raw_record_owner_mapping FROM 'raw-record-owner-mapping.csv' WITH (FORMAT csv, HEADER match)
\ir validate-raw-record-owners.sql

SELECT r.id AS raw_record_id, r.institution_id AS previous_owner,
       m.organization_id AS new_owner
FROM raw_record r JOIN raw_record_owner_mapping m ON m.raw_record_id = r.id
ORDER BY r.id;
SELECT count(*) AS records_to_migrate FROM raw_record_owner_mapping;

\if :apply
UPDATE raw_record r
SET institution_id = m.organization_id::text
FROM raw_record_owner_mapping m
WHERE r.id = m.raw_record_id;
COMMIT;
\else
ROLLBACK;
\endif
