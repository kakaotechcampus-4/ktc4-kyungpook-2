-- 웹에서 업로드 → 매칭 → 검증을 확인하기 위한 테스트 아이 명부. psql 전용 (#130).
--
-- 아이 등록 API 는 PENDING_CONSENT 로 넣는데, 매칭 명부는 ACTIVE 아이만 쓰고 ACTIVE 로 바꾸는
-- 기능(보호자 동의)이 아직 없다. 그 전까지 이 스크립트로 ACTIVE 아이를 직접 넣는다.
--
-- 명부는 AI/evals/manifests/잇다_synthetic_100.json 의 등록 100명(dev 80 + holdout 20)이다.
-- AI 채점과 같은 명부다 (AI/evals/scripts/matching/build_inputs.py — "명부는 문제가 아니라 보기").
-- 미등록 50명은 "명부에 없는 아이" 케이스라 넣지 않는다. 업로드할 일지는 dev 만 쓴다.
--
-- 테스트 아이는 위 100명의 (이름, 생년월일)로 알아본다. 표시용 컬럼이 없어서다.
--
-- 사용법 (서버 infra/docker 에서). 기본은 결과만 보여주고 ROLLBACK, 저장하려면 -v apply=true.
--   dbsql() { docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' sh "$@"; }
--   dbsql -v action=seed   -v org_id=5 < ../../backend/scripts/test-children.sql   # 100명을 기관 5 명부에 넣기
--   dbsql -v action=unlink -v org_id=5 < ../../backend/scripts/test-children.sql   # 기관 5 명부에서만 빼기
--   dbsql -v action=purge              < ../../backend/scripts/test-children.sql   # 모든 기관에서 빼고 아이도 삭제
--
-- 삭제는 앱과 같이 deleted_at 을 채운다. matching_result·validation_result 가 child_id 를 FK 없이
-- 들고 있어서, 행을 지우면 그 결과들이 없는 아이를 가리키게 된다.
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
-- 앱 트랜잭션이 잡고 있으면 무한정 기다리지 않고 멈춘다 (기다리는 동안 아이 등록도 막힌다).
SET LOCAL lock_timeout = '5s';
-- 확인과 쓰기 사이에 다른 등록이 끼어들어 같은 아이가 둘이 되지 않게 한다.
LOCK TABLE child, child_organization IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE test_params ON COMMIT DROP AS
SELECT :'action'::text AS action, NULLIF(:'org_id', '')::bigint AS org_id;

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
    -- 등록 API 로 들어온 아이일 수 있다. ACTIVE 로 바꾸거나 지우지 않는다.
    SELECT string_agg(format('%s(%s): child.id %s %s', c.name, c.birthdate, c.id, c.status), ', ' ORDER BY c.id)
    INTO conflict
    FROM child c JOIN test_child t ON t.name = c.name AND t.birthdate = c.birthdate
    WHERE c.deleted_at IS NULL AND c.status <> 'ACTIVE';
    IF conflict IS NOT NULL THEN
        RAISE EXCEPTION 'a child with the same name and birthdate as a test child is not ACTIVE: %', conflict;
    END IF;
END
$$;

SELECT action = 'seed' AS is_seed, action = 'unlink' AS is_unlink, action = 'purge' AS is_purge
FROM test_params \gset

\if :is_seed
INSERT INTO child (name, birthdate, status, created_at, updated_at)
SELECT t.name, t.birthdate, 'ACTIVE', now(), now()
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
\endif

\if :is_unlink
UPDATE child_organization co
SET deleted_at = now(), updated_at = now()
FROM test_child_id t, test_params p
WHERE co.child_id = t.child_id AND co.organization_id = p.org_id AND co.deleted_at IS NULL;
\endif

\if :is_purge
UPDATE child_organization co
SET deleted_at = now(), updated_at = now()
FROM test_child_id t
WHERE co.child_id = t.child_id AND co.deleted_at IS NULL;

UPDATE child c
SET deleted_at = now(), updated_at = now()
FROM test_child_id t
WHERE c.id = t.child_id;
\endif

-- 결과: 남아 있는 테스트 아이 수와 기관별 연결 수
SELECT count(*) AS active_test_children
FROM child c JOIN test_child t ON t.name = c.name AND t.birthdate = c.birthdate
WHERE c.deleted_at IS NULL;

SELECT co.organization_id, o.name AS organization_name, count(*) AS linked_test_children
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
