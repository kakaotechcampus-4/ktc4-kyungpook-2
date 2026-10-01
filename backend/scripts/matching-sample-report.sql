-- 매칭 워커 연동 확인 결과. generate-matching-sample-data.py 로 넣은 데이터에만 쓴다 (로컬 전용).
--
-- 주의: "명확" 케이스는 표지 힌트가 곧 정답이라 auto 비율이 실제 운영보다 좋게 나온다.
-- AI 판정의 정확도는 AI 팀 채점(AI/evals)이 기준이다. 여기서는 BE 가 입력을 제대로 보내고
-- 결과를 제대로 남기는지만 본다.

-- 1. 노션 4번 확인 SQL (전체)
SELECT status, count(*) FROM matching_result GROUP BY status ORDER BY status;

-- 2. 일지 상태 (확인용 데이터만)
SELECT je.status, count(*)
FROM journal_entry je
JOIN matching_eval_expected e ON e.journal_entry_id = je.id
GROUP BY je.status
ORDER BY je.status;

-- 3. 난이도별 결과. 일지 하나에 결과가 여러 행이면 최신 행이 유효하다 (DB 스키마 §7.1).
WITH latest AS (
    SELECT DISTINCT ON (journal_entry_id) *
    FROM matching_result
    ORDER BY journal_entry_id, created_at DESC, id DESC
)
SELECT
    e.difficulty                                                                              AS 난이도,
    count(*)                                                                                  AS 전체,
    count(*) FILTER (WHERE m.status = 'AUTO' AND m.matched_child_id = e.expected_child_id)    AS auto_정답,
    count(*) FILTER (WHERE m.status = 'AUTO' AND m.matched_child_id IS DISTINCT FROM e.expected_child_id) AS auto_오답,
    count(*) FILTER (WHERE m.status IN ('REVIEW', 'MULTI'))                                   AS review_multi,
    count(*) FILTER (WHERE m.status = 'UNMATCHED')                                            AS unmatched,
    count(*) FILTER (WHERE m.status = 'FAILED')                                               AS failed,
    count(*) FILTER (WHERE m.id IS NULL)                                                      AS 미처리
FROM matching_eval_expected e
LEFT JOIN latest m ON m.journal_entry_id = e.journal_entry_id
GROUP BY e.difficulty
ORDER BY e.difficulty;

-- 4. 확정 아동이 일지에 제대로 들어갔는지 (MATCHED 인데 child_id 가 다르거나 비어 있으면 BE 버그)
SELECT count(*) AS matched_child_불일치
FROM journal_entry je
JOIN matching_result m ON m.journal_entry_id = je.id AND m.status = 'AUTO' AND m.reviewer_id IS NULL
WHERE je.status = 'MATCHED' AND je.child_id IS DISTINCT FROM m.matched_child_id;
