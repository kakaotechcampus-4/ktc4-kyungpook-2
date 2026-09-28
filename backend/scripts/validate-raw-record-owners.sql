-- migrate-raw-record-owners.sql 의 트랜잭션과 잠금 안에서 실행한다.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM raw_record_owner_mapping) THEN
        RAISE EXCEPTION 'Owner mapping is empty';
    END IF;
    IF EXISTS (
        SELECT raw_record_id FROM raw_record_owner_mapping
        GROUP BY raw_record_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Duplicate raw_record_id in owner mapping';
    END IF;
    -- 전환 중 쓰기를 중단한 상태에서 모든 기존 기록을 대상으로 한다.
    IF EXISTS (
        SELECT 1 FROM raw_record r
        LEFT JOIN raw_record_owner_mapping m ON m.raw_record_id = r.id
        WHERE m.raw_record_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Owner mapping is missing existing raw records';
    END IF;
    IF EXISTS (
        SELECT 1 FROM raw_record_owner_mapping m
        LEFT JOIN raw_record r ON r.id = m.raw_record_id
        WHERE r.id IS NULL OR r.institution_id IS DISTINCT FROM m.legacy_kakao_id
    ) THEN
        RAISE EXCEPTION 'Raw record is missing or its previous owner does not match';
    END IF;
    IF EXISTS (
        SELECT 1 FROM raw_record_owner_mapping m
        LEFT JOIN organization o ON o.id = m.organization_id
        WHERE o.id IS NULL
    ) THEN
        RAISE EXCEPTION 'Target organization does not exist';
    END IF;
    IF EXISTS (
        SELECT 1 FROM raw_record_owner_mapping m
        WHERE NOT EXISTS (
            SELECT 1 FROM users u
            WHERE u.kakao_id = m.legacy_kakao_id
              AND u.deleted_at IS NULL AND u.role = 'ORGANIZATION'
              AND u.organization_id = m.organization_id
        )
    ) THEN
        RAISE EXCEPTION 'Previous owner is not an active member of the target organization';
    END IF;
END;
$$;
