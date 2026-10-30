-- psql 전용. 시스템 오류(SYSTEM_ERROR)로 멈춘 일지를 원인을 고친 뒤 다시 처리하도록 되돌린다 (#180).
-- 단계와 원인을 함께 골라야 한다. 고친 원인의 일지만 되돌리기 위해서다.
--   -v stage=VALIDATION | SUMMARY
--   -v reason=REQUEST_REJECTED | RESPONSE_UNREADABLE | EVIDENCE_MISMATCH | AGENT_UNAVAILABLE | INTERNAL_ERROR
-- 기본은 대상만 보여주고 ROLLBACK. 적용하려면 -v apply=true 를 지정한다.
--
-- 검증(VALIDATION)은 검증 대기(MATCHED)로, 요약(SUMMARY)은 요약 대기(VALIDATED)로 돌리고 실패 단계·원인을 비운다.
-- 요약 대기로 돌린 일지는 그 묶음에서 [요약](O-32)을 다시 눌러야 요약된다. 자동 요약은 없다 (#181).
-- 아이가 정해지지 않은 채 검증에서 멈춘 일지는 검증 대기로 돌려도 워커가 집지 않으므로 되돌리지 않고 따로 보여준다.
\set ON_ERROR_STOP on
\if :{?apply}
\else
\set apply false
\endif
\if :{?stage}
\else
\set stage ''
\endif
\if :{?reason}
\else
\set reason ''
\endif

BEGIN;
SELECT :'apply'::boolean AS apply_requested, :'stage' AS stage, :'reason' AS reason;

CREATE TEMP TABLE recover_params ON COMMIT DROP AS
SELECT :'stage'::text AS stage, :'reason'::text AS reason;

DO $$
DECLARE
    p recover_params%ROWTYPE;
BEGIN
    SELECT * INTO p FROM recover_params;
    IF p.stage NOT IN ('VALIDATION', 'SUMMARY') THEN
        RAISE EXCEPTION 'stage 는 VALIDATION 또는 SUMMARY 여야 합니다: "%"', p.stage;
    END IF;
    IF p.reason NOT IN ('REQUEST_REJECTED', 'RESPONSE_UNREADABLE', 'EVIDENCE_MISMATCH',
                        'AGENT_UNAVAILABLE', 'INTERNAL_ERROR') THEN
        RAISE EXCEPTION 'reason 이 원인 코드가 아닙니다: "%"', p.reason;
    END IF;
END $$;

CREATE TEMP TABLE recover_targets ON COMMIT DROP AS
SELECT e.id, e.child_id, e.entry_date, e.updated_at AS stopped_at
FROM journal_entry e JOIN recover_params p
  ON e.failure_stage = p.stage AND e.failure_reason = p.reason
WHERE e.status = 'SYSTEM_ERROR' AND e.deleted_at IS NULL;

SELECT id AS journal_entry_id, child_id, entry_date, stopped_at
FROM recover_targets
WHERE child_id IS NOT NULL
ORDER BY id;
SELECT count(*) FILTER (WHERE child_id IS NOT NULL) AS entries_to_recover,
       count(*) FILTER (WHERE child_id IS NULL) AS entries_without_child_skipped
FROM recover_targets;
-- 아이가 없는 일지는 매칭부터 다시 봐야 한다. id 만 보여주고 손대지 않는다.
SELECT id AS skipped_journal_entry_id_without_child FROM recover_targets WHERE child_id IS NULL ORDER BY id;

\if :apply
UPDATE journal_entry e
SET status = CASE p.stage WHEN 'VALIDATION' THEN 'MATCHED' ELSE 'VALIDATED' END,
    agent_retry_count = CASE p.stage WHEN 'VALIDATION' THEN 0 ELSE e.agent_retry_count END,
    failure_stage = NULL,
    failure_reason = NULL,
    -- 앱은 한국 시간으로 남긴다. 배포 DB 는 UTC 로 돌아서 localtimestamp 를 쓰면 9시간 어긋난다.
    updated_at = now() AT TIME ZONE 'Asia/Seoul'
FROM recover_targets t, recover_params p
-- 대상을 고른 뒤 다른 실행이 되돌려 워커가 다시 실패로 남겼을 수 있다. 아직 고른 단계·원인 그대로인 행만 바꾼다.
WHERE e.id = t.id AND t.child_id IS NOT NULL AND e.status = 'SYSTEM_ERROR'
  AND e.failure_stage = p.stage AND e.failure_reason = p.reason
RETURNING e.id AS recovered_journal_entry_id, e.status;
COMMIT;
\else
ROLLBACK;
\endif
