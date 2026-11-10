SELECT status, count(*) FROM matching_result GROUP BY status ORDER BY status;

SELECT je.status, count(*)
FROM journal_entry je
JOIN matching_eval_expected e ON e.journal_entry_id = je.id
GROUP BY je.status
ORDER BY je.status;

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

SELECT count(*) AS matched_child_불일치
FROM journal_entry je
JOIN matching_result m ON m.journal_entry_id = je.id AND m.status = 'AUTO' AND m.reviewer_id IS NULL
WHERE je.status = 'MATCHED' AND je.child_id IS DISTINCT FROM m.matched_child_id;
