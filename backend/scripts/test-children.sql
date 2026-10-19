-- 웹에서 업로드 → 매칭 → 검증을 확인하기 위한 테스트 아이 명부. psql 전용 (#130, #184).
--
-- 매칭 명부는 "그 기관에 대한 보호자 동의(sharing_consent ACTIVE)"가 있는 아이만 쓴다. 테스트 아이 100명에게
-- 일일이 초대 코드 수락과 동의를 할 수 없으니, 이 스크립트가 앱과 같은 행을 직접 만든다:
--   테스트 보호자(users) → 사용된 초대(invitation ACCEPTED) → 보호자 연결(child_guardian) → 동의(sharing_consent)
--
-- 명부는 AI/evals/manifests/잇다_synthetic_100.json 의 등록 100명(dev 80 + holdout 20)이다.
-- AI 채점과 같은 명부다 (AI/evals/scripts/matching/build_inputs.py — "명부는 문제가 아니라 보기").
-- 미등록 50명은 "명부에 없는 아이" 케이스라 넣지 않는다. 업로드할 일지는 dev 만 쓴다.
--
-- 테스트 아이는 위 100명의 (이름, 생년월일)로 찾고, 테스트 보호자와의 연결로 "테스트 아이가 맞다"를 확인한다.
-- 표시용 컬럼이 없어서다. 같은 이름·생일의 실제 아이(등록 API 로 만들었거나 실제 보호자가 연결된 아이)는 건드리지 않는다.
--
-- 사용법 (서버 infra/docker 에서). 기본은 결과만 보여주고 ROLLBACK, 저장하려면 -v apply=true.
-- sharing_consent 테이블이 생기는 BE 를 먼저 배포해야 한다. 테이블이 없으면 바로 멈춘다.
--   dbsql() { docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' sh "$@"; }
--   dbsql -v action=seed   -v org_id=5 < ../../backend/scripts/test-children.sql   # 100명을 기관 5 명부에 넣고 동의까지
--   dbsql -v action=unlink -v org_id=5 < ../../backend/scripts/test-children.sql   # 기관 5 명부에서만 빼고 그 동의 철회
--   dbsql -v action=purge              < ../../backend/scripts/test-children.sql   # 모든 기관에서 빼고 동의 철회, 아이 삭제
--
-- seed 는 다시 돌려도 빠진 것만 채운다. 테스트 보호자와 이미 연결된 아이에게는 초대 행을 새로 만들지 않는다.
-- 동의 철회는 앱과 같이 REVOKED 로 남긴다(revoke_reason = ROSTER_REMOVED, revoked_by 는 비움). 삭제는 deleted_at 만 채운다.
-- matching_result·validation_result 가 child_id 를 FK 없이 들고 있어서, 행을 지우면 그 결과들이 없는 아이를 가리키게 된다.
-- 기관 id 는 SELECT u.name, o.id, o.name FROM users u JOIN organization o ON o.id = u.organization_id;
-- 실행했으면 #130 에 기관과 action 을 댓글로 남긴다.
\set ON_ERROR_STOP on
\if :{?apply}
\else
\set apply false
\endif
\if :{?action}
\else
\set action ''
\endif
\if :{?org_id}
\else
\set org_id ''
\endif

BEGIN;
SELECT :'apply'::boolean AS apply_requested;
-- db 컨테이너는 UTC 인데 BE 는 Asia/Seoul 로 created_at 을 쓴다. 같은 기준으로 남긴다.
SET LOCAL TimeZone = 'Asia/Seoul';
-- 앱 트랜잭션이 잡고 있으면 무한정 기다리지 않고 멈춘다 (기다리는 동안 아이 등록·동의도 막힌다).
SET LOCAL lock_timeout = '5s';

-- 동의 테이블은 BE 가 만든다(ddl-auto). 새 BE 배포 전이면 아무것도 하지 않고 멈춘다.
DO $$
BEGIN
    IF to_regclass('sharing_consent') IS NULL THEN
        RAISE EXCEPTION 'sharing_consent table does not exist. deploy the backend with consent support first';
    END IF;
END
$$;

-- 확인과 쓰기 사이에 다른 등록·연결·동의가 끼어들지 않게 한다. 앱의 잠금 순서와 같은 순서로 잡는다 (DB 스키마 §3.3).
LOCK TABLE child, child_organization, invitation, child_guardian, sharing_consent IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE test_params ON COMMIT DROP AS
SELECT :'action'::text AS action, NULLIF(:'org_id', '')::bigint AS org_id,
       'test-guardian-130'::varchar(50) AS guardian_kakao_id,
       '["daily_summary","weekly_insight"]'::text AS scope;

CREATE TEMP TABLE test_child (
    manifest_child_id text PRIMARY KEY,
    name varchar(100) NOT NULL,
    birthdate date NOT NULL,
    UNIQUE (name, birthdate)
) ON COMMIT DROP;
INSERT INTO test_child (manifest_child_id, name, birthdate) VALUES
    ('dev_child_001', '송준호', DATE '2019-11-26'),
    ('dev_child_002', '송민우', DATE '2017-05-08'),
    ('dev_child_003', '임은수', DATE '2017-05-23'),
    ('dev_child_004', '안다인', DATE '2017-06-04'),
    ('dev_child_005', '김지민', DATE '2019-07-22'),
    ('dev_child_006', '안하은', DATE '2020-07-13'),
    ('dev_child_007', '김현우', DATE '2016-06-19'),
    ('dev_child_008', '박서윤', DATE '2019-02-26'),
    ('dev_child_009', '박도영', DATE '2020-03-24'),
    ('dev_child_010', '윤지민', DATE '2019-10-01'),
    ('dev_child_011', '한소율', DATE '2016-06-10'),
    ('dev_child_012', '장은솔', DATE '2016-11-28'),
    ('dev_child_013', '이하윤', DATE '2018-11-03'),
    ('dev_child_014', '백지안', DATE '2018-12-11'),
    ('dev_child_015', '정은솔', DATE '2017-02-13'),
    ('dev_child_016', '제지민', DATE '2020-07-11'),
    ('dev_child_017', '정서윤', DATE '2016-11-19'),
    ('dev_child_018', '제시우', DATE '2018-12-04'),
    ('dev_child_019', '윤수아', DATE '2018-07-15'),
    ('dev_child_020', '제채원', DATE '2017-07-10'),
    ('dev_child_021', '임은우', DATE '2017-06-11'),
    ('dev_child_022', '백채원', DATE '2018-06-09'),
    ('dev_child_023', '김은서', DATE '2019-04-27'),
    ('dev_child_024', '이도현', DATE '2019-05-14'),
    ('dev_child_025', '한지호', DATE '2016-01-23'),
    ('dev_child_026', '윤동현', DATE '2017-03-01'),
    ('dev_child_027', '최소윤', DATE '2017-01-10'),
    ('dev_child_028', '최소율', DATE '2017-01-11'),
    ('dev_child_029', '백지원', DATE '2018-12-29'),
    ('dev_child_030', '윤민우', DATE '2019-11-06'),
    ('dev_child_031', '송지민', DATE '2020-04-16'),
    ('dev_child_032', '정지원', DATE '2019-12-22'),
    ('dev_child_033', '한은수', DATE '2018-07-13'),
    ('dev_child_034', '조지안', DATE '2020-05-15'),
    ('dev_child_035', '정은우', DATE '2019-12-17'),
    ('dev_child_036', '김유나', DATE '2019-04-23'),
    ('dev_child_037', '윤승민', DATE '2017-11-28'),
    ('dev_child_038', '안은솔', DATE '2016-11-08'),
    ('dev_child_039', '주동현', DATE '2018-12-04'),
    ('dev_child_040', '주은솔', DATE '2019-01-14'),
    ('dev_child_041', '임예은', DATE '2016-04-18'),
    ('dev_child_042', '한아름', DATE '2016-09-28'),
    ('dev_child_043', '주소율', DATE '2018-12-21'),
    ('dev_child_044', '장시완', DATE '2018-08-26'),
    ('dev_child_045', '임지민', DATE '2019-09-09'),
    ('dev_child_046', '정하윤', DATE '2019-12-01'),
    ('dev_child_047', '한은서', DATE '2017-08-08'),
    ('dev_child_048', '제예린', DATE '2020-10-23'),
    ('dev_child_049', '윤서연', DATE '2018-10-01'),
    ('dev_child_050', '장하윤', DATE '2017-08-18'),
    ('dev_child_051', '안도현', DATE '2020-02-06'),
    ('dev_child_052', '박민우', DATE '2020-10-09'),
    ('dev_child_053', '이시우', DATE '2016-03-25'),
    ('dev_child_054', '안현우', DATE '2019-08-03'),
    ('dev_child_055', '최민준', DATE '2016-01-15'),
    ('dev_child_056', '최채원', DATE '2018-03-04'),
    ('dev_child_057', '백다인', DATE '2020-06-10'),
    ('dev_child_058', '임예린', DATE '2016-10-20'),
    ('dev_child_059', '제도영', DATE '2019-03-07'),
    ('dev_child_060', '한은우', DATE '2018-03-02'),
    ('dev_child_061', '김예은', DATE '2017-04-20'),
    ('dev_child_062', '제서윤', DATE '2016-03-17'),
    ('dev_child_063', '박지원', DATE '2016-12-05'),
    ('dev_child_064', '안태오', DATE '2017-01-24'),
    ('dev_child_065', '임가은', DATE '2020-02-05'),
    ('dev_child_066', '이동현', DATE '2020-06-27'),
    ('dev_child_067', '송민준', DATE '2017-05-06'),
    ('dev_child_068', '한지후', DATE '2016-01-30'),
    ('dev_child_069', '김재현', DATE '2018-08-19'),
    ('dev_child_070', '윤가은', DATE '2016-10-11'),
    ('dev_child_071', '윤시우', DATE '2016-09-22'),
    ('dev_child_072', '제서연', DATE '2016-03-23'),
    ('dev_child_073', '주민우', DATE '2016-12-22'),
    ('dev_child_074', '백재현', DATE '2016-06-01'),
    ('dev_child_075', '주서윤', DATE '2016-06-09'),
    ('dev_child_076', '이지후', DATE '2018-06-13'),
    ('dev_child_077', '조은수', DATE '2018-11-26'),
    ('dev_child_078', '김태오', DATE '2017-01-23'),
    ('dev_child_079', '임소윤', DATE '2020-09-10'),
    ('dev_child_080', '최태경', DATE '2019-06-02'),
    ('holdout_child_001', '백지민', DATE '2020-07-18'),
    ('holdout_child_002', '한태영', DATE '2018-12-02'),
    ('holdout_child_003', '김예린', DATE '2017-04-27'),
    ('holdout_child_004', '안도영', DATE '2020-02-21'),
    ('holdout_child_005', '윤하윤', DATE '2018-03-28'),
    ('holdout_child_006', '한태경', DATE '2018-12-01'),
    ('holdout_child_007', '제수아', DATE '2017-10-04'),
    ('holdout_child_008', '이소윤', DATE '2020-07-26'),
    ('holdout_child_009', '김지안', DATE '2017-07-17'),
    ('holdout_child_010', '한시우', DATE '2018-07-08'),
    ('holdout_child_011', '정아름', DATE '2018-09-18'),
    ('holdout_child_012', '조지원', DATE '2018-02-09'),
    ('holdout_child_013', '안하윤', DATE '2020-07-07'),
    ('holdout_child_014', '최민우', DATE '2020-04-16'),
    ('holdout_child_015', '장은서', DATE '2016-11-20'),
    ('holdout_child_016', '장소윤', DATE '2020-04-27'),
    ('holdout_child_017', '조예린', DATE '2017-09-28'),
    ('holdout_child_018', '최재현', DATE '2016-01-28'),
    ('holdout_child_019', '조지호', DATE '2016-07-16'),
    ('holdout_child_020', '김은우', DATE '2019-07-04');

DO $$
DECLARE
    p test_params%ROWTYPE;
    test_guardian_id bigint;
    conflict text;
BEGIN
    SELECT * INTO p FROM test_params;
    IF p.action NOT IN ('seed', 'unlink', 'purge') THEN
        RAISE EXCEPTION 'action must be seed, unlink or purge (got "%")', p.action;
    END IF;
    IF p.action IN ('seed', 'unlink') AND p.org_id IS NULL THEN
        RAISE EXCEPTION 'org_id is required for %', p.action;
    END IF;
    -- purge 는 모든 기관에서 뺀다. "내 기관만"으로 착각하고 남의 명부까지 지우지 않게 막는다.
    IF p.action = 'purge' AND p.org_id IS NOT NULL THEN
        RAISE EXCEPTION 'purge takes no org_id (use unlink to remove from one organization)';
    END IF;
    IF p.org_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM organization WHERE id = p.org_id) THEN
        RAISE EXCEPTION 'organization % does not exist', p.org_id;
    END IF;
    -- 같은 kakao_id 로 보호자가 아닌 회원이 있으면 테스트 보호자로 쓸 수 없다.
    IF EXISTS (SELECT 1 FROM users WHERE kakao_id = p.guardian_kakao_id AND role IS DISTINCT FROM 'PARENT') THEN
        RAISE EXCEPTION 'user with kakao_id % exists but is not a PARENT', p.guardian_kakao_id;
    END IF;
    SELECT id INTO test_guardian_id FROM users WHERE kakao_id = p.guardian_kakao_id;

    -- 같은 (이름, 생년월일)이 둘이면 어느 쪽이 테스트 아이인지 알 수 없다.
    -- 등록 API 는 중복을 막지 않아서 테스트 이름으로 한 번 등록하면 생긴다. 직접 정리할 수 있게 id 를 알려준다.
    SELECT string_agg(format('%s(%s): child.id %s', t.name, t.birthdate, ids), ', ')
    INTO conflict
    FROM test_child t
    JOIN (
        SELECT c.name, c.birthdate, string_agg(c.id || ' ' || c.status, ', ' ORDER BY c.id) AS ids
        FROM child c WHERE c.deleted_at IS NULL
        GROUP BY c.name, c.birthdate HAVING count(*) > 1
    ) d ON d.name = t.name AND d.birthdate = t.birthdate;
    IF conflict IS NOT NULL THEN
        RAISE EXCEPTION 'more than one child has the same name and birthdate as a test child: %', conflict;
    END IF;

    -- 테스트 아이로 인정하는 경우만 바꾼다. 상태(ACTIVE 여부)로는 가르지 않는다 — unlink 하면 테스트 아이도
    -- SUSPENDED 가 되므로, 상태로 가르면 다시 seed 할 수 없다.
    --   (1) 테스트 보호자와 연결된 적이 있고(해제 포함), 다른 사람의 연결·동의가 하나도 없다
    --   (2) 이 스크립트의 예전 판(#130)이 넣은 아이: ACTIVE 이고 연결·동의가 하나도 없다
    -- 그 밖의 아이(등록 API 로 만든 PENDING_CONSENT 아이, 실제 보호자가 연결된 아이)는 건드리지 않고 멈춘다.
    SELECT string_agg(format('%s(%s): child.id %s %s', c.name, c.birthdate, c.id, c.status), ', ' ORDER BY c.id)
    INTO conflict
    FROM child c JOIN test_child t ON t.name = c.name AND t.birthdate = c.birthdate
    WHERE c.deleted_at IS NULL
      AND NOT (
          (test_guardian_id IS NOT NULL
              AND EXISTS (SELECT 1 FROM child_guardian g WHERE g.child_id = c.id AND g.user_id = test_guardian_id)
              AND NOT EXISTS (SELECT 1 FROM child_guardian g WHERE g.child_id = c.id AND g.user_id <> test_guardian_id)
              AND NOT EXISTS (SELECT 1 FROM sharing_consent sc WHERE sc.child_id = c.id AND sc.guardian_id <> test_guardian_id))
          OR (c.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM child_guardian g WHERE g.child_id = c.id)
              AND NOT EXISTS (SELECT 1 FROM sharing_consent sc WHERE sc.child_id = c.id))
      );
    IF conflict IS NOT NULL THEN
        RAISE EXCEPTION 'a child with the same name and birthdate as a test child is not a test child: %', conflict;
    END IF;
END
$$;

SELECT action = 'seed' AS is_seed, action = 'unlink' AS is_unlink, action = 'purge' AS is_purge
FROM test_params \gset

\if :is_seed
-- 테스트 보호자. 탈퇴 표시돼 있으면 되살린다(kakao_id 는 UNIQUE 라 새로 넣을 수 없다).
INSERT INTO users (kakao_id, name, role, created_at, updated_at)
SELECT p.guardian_kakao_id, '테스트 보호자', 'PARENT', now(), now()
FROM test_params p
ON CONFLICT (kakao_id) DO NOTHING;

UPDATE users u
SET deleted_at = NULL, updated_at = now()
FROM test_params p
WHERE u.kakao_id = p.guardian_kakao_id AND u.deleted_at IS NOT NULL;

-- 새 아이는 앱과 같이 동의 대기로 넣는다. 아래에서 동의를 넣은 뒤 상태를 다시 계산한다.
INSERT INTO child (name, birthdate, status, created_at, updated_at)
SELECT t.name, t.birthdate, 'PENDING_CONSENT', now(), now()
FROM test_child t
WHERE NOT EXISTS (
    SELECT 1 FROM child c
    WHERE c.name = t.name AND c.birthdate = t.birthdate AND c.deleted_at IS NULL
);
\endif

CREATE TEMP TABLE test_child_id ON COMMIT DROP AS
SELECT t.manifest_child_id, c.id AS child_id
FROM test_child t
JOIN child c ON c.name = t.name AND c.birthdate = t.birthdate AND c.deleted_at IS NULL;

\if :is_seed
-- (child_id, organization_id) 는 UNIQUE 라서 지웠던 연결은 새로 넣지 않고 되살린다.
UPDATE child_organization co
SET deleted_at = NULL, updated_at = now()
FROM test_child_id t, test_params p
WHERE co.child_id = t.child_id AND co.organization_id = p.org_id AND co.deleted_at IS NOT NULL;

INSERT INTO child_organization (child_id, organization_id, created_at, updated_at)
SELECT t.child_id, p.org_id, now(), now()
FROM test_child_id t, test_params p
WHERE NOT EXISTS (
    SELECT 1 FROM child_organization co
    WHERE co.child_id = t.child_id AND co.organization_id = p.org_id
);

-- 보호자 연결. 이미 살아 있는 연결이 있으면 아무것도 하지 않는다 — 초대 행도 만들지 않는다.
-- 연결이 없거나 해제돼 있을 때만, 앱의 초대 수락처럼 "사용된 초대"를 하나 만들고 그 초대로 연결한다(child_guardian 의
-- invitation_id 는 비울 수 없다). 코드는 앱과 같은 31자 알파벳 8자리이고, 겹치면 다시 뽑는다.
DO $$
DECLARE
    p test_params%ROWTYPE;
    test_guardian_id bigint;
    target record;
    new_code text;
    new_invitation_id bigint;
    alphabet constant text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
BEGIN
    SELECT * INTO p FROM test_params;
    SELECT id INTO STRICT test_guardian_id FROM users WHERE kakao_id = p.guardian_kakao_id;
    FOR target IN
        SELECT t.child_id, g.id AS link_id
        FROM test_child_id t
        LEFT JOIN child_guardian g ON g.child_id = t.child_id AND g.user_id = test_guardian_id
        WHERE g.id IS NULL OR g.deleted_at IS NOT NULL
        ORDER BY t.child_id
    LOOP
        LOOP
            SELECT string_agg(substr(alphabet, 1 + floor(random() * length(alphabet))::int, 1), '')
            INTO new_code
            FROM generate_series(1, 8);
            EXIT WHEN NOT EXISTS (SELECT 1 FROM invitation WHERE code = new_code);
        END LOOP;
        INSERT INTO invitation (child_id, organization_id, code, status, accepted_user_id, expires_at, created_at, updated_at)
        VALUES (target.child_id, p.org_id, new_code, 'ACCEPTED', test_guardian_id, now(), now(), now())
        RETURNING id INTO new_invitation_id;

        IF target.link_id IS NULL THEN
            INSERT INTO child_guardian (child_id, user_id, invitation_id, linked_at, created_at, updated_at)
            VALUES (target.child_id, test_guardian_id, new_invitation_id, now(), now(), now());
        ELSE
            UPDATE child_guardian
            SET deleted_at = NULL, invitation_id = new_invitation_id, linked_at = now(), updated_at = now()
            WHERE id = target.link_id;
        END IF;
    END LOOP;
END
$$;

-- 이 기관에 대한 테스트 보호자의 살아 있는 동의가 없을 때만 새로 넣는다.
INSERT INTO sharing_consent (child_id, guardian_id, organization_id, scope, status, created_at, updated_at)
SELECT t.child_id, u.id, p.org_id, p.scope, 'ACTIVE', now(), now()
FROM test_child_id t
CROSS JOIN test_params p
JOIN users u ON u.kakao_id = p.guardian_kakao_id
WHERE NOT EXISTS (
    SELECT 1 FROM sharing_consent sc
    WHERE sc.child_id = t.child_id AND sc.organization_id = p.org_id
      AND sc.guardian_id = u.id AND sc.status = 'ACTIVE'
);

-- 같은 기관의 살아 있는 동의는 같은 공유 항목을 가진다 (앱 규칙). 이미 있던 동의도 이 값으로 맞춘다.
UPDATE sharing_consent sc
SET scope = p.scope, updated_at = now()
FROM test_child_id t, test_params p
WHERE sc.child_id = t.child_id AND sc.organization_id = p.org_id AND sc.status = 'ACTIVE'
  AND sc.scope IS DISTINCT FROM p.scope;
\endif

\if :is_unlink
UPDATE child_organization co
SET deleted_at = now(), updated_at = now()
FROM test_child_id t, test_params p
WHERE co.child_id = t.child_id AND co.organization_id = p.org_id AND co.deleted_at IS NULL;

-- 명부에서 빠진 기관에 대한 동의는 끈다. 사람이 끈 것이 아니라 revoked_by 는 비운다.
UPDATE sharing_consent sc
SET status = 'REVOKED', revoked_by = NULL, revoked_at = now(), revoke_reason = 'ROSTER_REMOVED', updated_at = now()
FROM test_child_id t, test_params p
WHERE sc.child_id = t.child_id AND sc.organization_id = p.org_id AND sc.status = 'ACTIVE';
\endif

\if :is_purge
UPDATE child_organization co
SET deleted_at = now(), updated_at = now()
FROM test_child_id t
WHERE co.child_id = t.child_id AND co.deleted_at IS NULL;

UPDATE sharing_consent sc
SET status = 'REVOKED', revoked_by = NULL, revoked_at = now(), revoke_reason = 'ROSTER_REMOVED', updated_at = now()
FROM test_child_id t
WHERE sc.child_id = t.child_id AND sc.status = 'ACTIVE';
\endif

-- 아이 상태는 앱과 같은 규칙으로 다시 계산한다: 살아 있는 동의가 있으면 ACTIVE, 동의 행은 있지만 모두 꺼졌으면
-- SUSPENDED, 동의 행이 없으면 그대로(예전 판이 넣은 ACTIVE 아이는 seed 에서 동의가 생긴다).
UPDATE child c
SET status = CASE
        WHEN EXISTS (SELECT 1 FROM sharing_consent sc WHERE sc.child_id = c.id AND sc.status = 'ACTIVE') THEN 'ACTIVE'
        ELSE 'SUSPENDED'
    END,
    updated_at = now()
FROM test_child_id t
WHERE c.id = t.child_id
  AND EXISTS (SELECT 1 FROM sharing_consent sc WHERE sc.child_id = c.id)
  AND c.status IS DISTINCT FROM CASE
        WHEN EXISTS (SELECT 1 FROM sharing_consent sc WHERE sc.child_id = c.id AND sc.status = 'ACTIVE') THEN 'ACTIVE'
        ELSE 'SUSPENDED'
    END;

\if :is_purge
UPDATE child c
SET deleted_at = now(), updated_at = now()
FROM test_child_id t
WHERE c.id = t.child_id;
\endif

-- 결과: 남아 있는 테스트 아이 수와 상태
SELECT c.status, count(*) AS test_children
FROM child c JOIN test_child t ON t.name = c.name AND t.birthdate = c.birthdate
WHERE c.deleted_at IS NULL
GROUP BY c.status
ORDER BY c.status;

-- 기관별 명부 연결 수와 매칭 명부에 들어가는 수(그 기관에 살아 있는 동의가 있는 아이)
SELECT co.organization_id, o.name AS organization_name, count(*) AS linked_test_children,
       count(*) FILTER (WHERE EXISTS (
           SELECT 1 FROM sharing_consent sc
           WHERE sc.child_id = c.id AND sc.organization_id = co.organization_id AND sc.status = 'ACTIVE'
       )) AS consented_test_children
FROM child_organization co
JOIN organization o ON o.id = co.organization_id
JOIN child c ON c.id = co.child_id AND c.deleted_at IS NULL
JOIN test_child t ON t.name = c.name AND t.birthdate = c.birthdate
WHERE co.deleted_at IS NULL
GROUP BY co.organization_id, o.name
ORDER BY co.organization_id;

\if :is_seed
-- 정답 비교용 대응표 (JSON child_id ↔ DB child.id)
SELECT manifest_child_id, child_id FROM test_child_id ORDER BY manifest_child_id;
\endif

\if :apply
COMMIT;
\else
ROLLBACK;
\endif
