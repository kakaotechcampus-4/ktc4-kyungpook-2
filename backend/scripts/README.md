# 백엔드 운영 스크립트

서버 DB를 직접 고치는 도구입니다. 서버 시작 시 자동으로 돌지 않습니다. SQL 스크립트는 모두 **확인만 하고 되돌리는 것(ROLLBACK)이 기본**이고
`-v apply=true`를 줄 때만 저장합니다.

| 파일 | 하는 일 |
| --- | --- |
| [`test-children.sql`](test-children.sql) | 테스트 아이 100명을 기관 명부에 넣거나 뺀다 |
| [`recover-system-errors.sql`](recover-system-errors.sql) | 원인을 고친 뒤 `SYSTEM_ERROR`로 멈춘 일지를 다시 처리하게 한다 |
| [`migrate-raw-record-owners.sql`](migrate-raw-record-owners.sql) | 예전 버전의 원본 기록 소유자를 기관 ID로 옮긴다 (한 번만 쓰는 이전 도구) |
| [`generate-matching-sample-data.py`](generate-matching-sample-data.py) | 매칭 워커 확인용 가짜 데이터 SQL을 만든다. **로컬 전용** — 사용법은 `--help` |

아래 명령은 서버의 `infra/docker`에서 실행합니다. `dbsql`은 DB 컨테이너의 `psql`을 부르는 함수입니다.

```bash
dbsql() { docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' sh "$@"; }
```

## 테스트 아이 명부 넣기

웹에서 업로드 → 매칭 → 검증을 확인하려면 매칭 명부에 아이가 있어야 합니다. 매칭 명부는 **그 기관에 보호자 동의가 있는
아이**만 씁니다(DB 스키마 §5.1). 테스트 아이 100명에게 일일이 초대 코드 수락과 동의를 할 수 없으니,
[`test-children.sql`](test-children.sql)이 테스트 아이 100명(`잇다_synthetic_100.json`의 dev·holdout)과
함께 테스트 보호자·사용된 초대·보호자 연결·동의를 앱과 같은 모양으로 직접 넣습니다.

**새 BE를 먼저 배포합니다.** `sharing_consent` 테이블은 BE가 기동하며 만들고(Flyway 마이그레이션), 테이블이 없으면 스크립트가 바로 멈춥니다.
배포 직후부터 스크립트를 돌리기 전까지는 예전에 넣은 테스트 아이가 매칭 명부에서 빠집니다.

서버의 `infra/docker`에서 실행합니다. 기관 id는 이슈 #130 댓글이나 아래 조회로 확인합니다.

```bash
dbsql -c "SELECT o.id, o.name FROM organization o ORDER BY o.id;"   # 기관 id
dbsql -v action=seed -v org_id=5 < ../../backend/scripts/test-children.sql               # 확인만 (ROLLBACK)
dbsql -v action=seed -v org_id=5 -v apply=true < ../../backend/scripts/test-children.sql # 저장
```

- `seed`는 아이가 없으면 만들고 `org_id` 기관 명부에 연결한 뒤, 테스트 보호자(`kakao_id = test-guardian-130`)와 연결하고
  그 기관에 동의(`daily_summary`·`weekly_insight`)를 넣습니다. 다시 실행해도 빠진 것만 채웁니다 — 이미 연결된 아이에게는
  초대 행을 새로 만들지 않습니다. 예전 판 스크립트로 넣은 아이(`ACTIVE`, 연결·동의 없음)에게 돌리면 연결과 동의가 채워집니다.
- 여러 기관에 넣으면 같은 아이 행을 함께 쓰고, 기관마다 동의가 따로 생깁니다(등록 API는 기관마다 따로 만듭니다).
- `unlink`는 그 기관 명부에서만 빼고 그 기관 동의를 철회합니다. `purge`(`org_id`를 주면 거절)는 모든 기관에서 빼고 동의를
  모두 철회한 뒤 아이를 삭제 처리합니다. 철회는 `REVOKED`(`revoke_reason = ROSTER_REMOVED`)로 남고, 삭제는 `deleted_at`만 채웁니다.
- 아이 상태는 앱과 같은 규칙으로 다시 계산합니다(살아 있는 동의가 있으면 `ACTIVE`, 모두 철회됐으면 `SUSPENDED`).

테스트 아이와 이름·생년월일이 같은데 테스트 아이가 아닌 아이(등록 API로 만든 아이, 실제 보호자가 연결된 아이)가 있으면
아무것도 바꾸지 않고 멈추며, 오류에 그 `child.id`와 상태를 보여줍니다. 테스트하려고 등록한 아이라면 그 아이를 정리한 뒤
다시 실행합니다. 실행했으면 이슈 #130에 기관과 action을 남깁니다.

## 시스템 오류로 멈춘 일지 되돌리기

검증·요약 중 코드 버그나 AI 계약 위반으로 멈춘 일지는 `SYSTEM_ERROR`가 되고, 어느 단계(`failure_stage`)에서 왜
(`failure_reason`) 멈췄는지 남습니다(DB 스키마 §6.2 "실패 상태와 원인 코드"). 원인을 고친 뒤
[`recover-system-errors.sql`](recover-system-errors.sql)로 **고친 원인의 일지만** 골라 다시 처리하게 합니다.
위의 `dbsql`로 실행합니다.

```bash
dbsql -c "SELECT failure_stage, failure_reason, count(*) FROM journal_entry
          WHERE status = 'SYSTEM_ERROR' AND deleted_at IS NULL GROUP BY 1, 2;"                              # 원인별 건수
dbsql -v stage=VALIDATION -v reason=AGENT_UNAVAILABLE < ../../backend/scripts/recover-system-errors.sql             # 확인만 (ROLLBACK)
dbsql -v stage=VALIDATION -v reason=AGENT_UNAVAILABLE -v apply=true < ../../backend/scripts/recover-system-errors.sql # 되돌리기
```

- `stage`는 `VALIDATION`·`SUMMARY`, `reason`은 `REQUEST_REJECTED`·`RESPONSE_UNREADABLE`·`EVIDENCE_MISMATCH`·
  `AGENT_UNAVAILABLE`·`INTERNAL_ERROR` 중 하나입니다. 빠지거나 틀리면 아무것도 바꾸지 않고 멈춥니다.
- 검증은 검증 대기(`MATCHED`), 요약은 요약 대기(`VALIDATED`)로 돌아가고 두 실패 컬럼이 비워집니다. 검증은 워커가 다시 집습니다.
  요약은 자동 요약(`SUMMARY_AUTO_RUN`, 기본 켜짐)이면 워커가 다시 집습니다. 꺼져 있으면 그 묶음에서 [요약] 버튼을 다시 눌러야 집습니다 (#181).
- 아이가 정해지지 않은 일지는 검증 워커가 집지 않아 되돌리지 않고 `skipped_journal_entry_id_without_child`로 id만 보여줍니다.
- 매칭 단계에서 멈춘 일지(`failure_stage = MATCHING`, 원인 `CONTENT_TOO_LONG`)는 이 스크립트가 받지 않습니다. 기록이 너무 길어 멈춘 것이라 되돌려도 다시 멈춥니다.
- 버그를 고치기 전에 되돌리면 같은 원인으로 다시 멈춥니다. 서버 시작 시 자동 실행되지 않습니다.

**요약 실패(`SUMMARY_FAILED`)는 선생님이 검토 화면에서 처리합니다** (#369). 근거가 남은 문장이 없어 멈춘 일지라
요약 실패 목록에 묶음으로 뜨고, 선생님이 [다시 요약] 하거나 [요약 없이 마무리] 합니다. 화면이 들어오기
전에 관리자가 한꺼번에 되돌려야 하면 아래처럼 요약 대기로 돌립니다. 같은 아이·날짜 묶음의 일지는 함께 돌려야 한 요약으로
묶입니다. 자동 요약이 꺼져 있으면 돌린 뒤 그 묶음에서 [요약] 버튼을 다시 눌러야 요약됩니다 (#181).

```bash
dbsql -c "SELECT id, child_id, entry_date FROM journal_entry
          WHERE status = 'SUMMARY_FAILED' AND deleted_at IS NULL ORDER BY child_id, entry_date, id;"   # 대상 확인
dbsql -c "UPDATE journal_entry SET status = 'VALIDATED', updated_at = now() AT TIME ZONE 'Asia/Seoul'
          WHERE status = 'SUMMARY_FAILED' AND deleted_at IS NULL AND id IN (101, 102);"                # 고른 id 만
```

## 기존 원본 기록의 기관 소유자 이전

> **기존 배포 데이터를 보존할 때 테이블을 삭제하지 마세요.** Flyway 이전에 쓰던 `ddl-auto: update`는
> 이전 실험 스키마의 `users.role` NOT NULL이나 `organization.name` UNIQUE 제약을 제거하지 못합니다.
> 기존 `organization` 행이 있다면 사업자등록번호도 실제 값으로 보완해야 합니다. 해당 스키마가 남아 있는
> 환경은 백업 후 별도의 스키마 변경을 먼저 준비해야 하며, 아래 도구는 소유자 데이터 이전만 담당합니다.

이전 버전은 `raw_record.institution_id`에 카카오 ID를 저장했습니다. 새 버전은 가입 시 만든 기관 ID를
저장하므로 기존 기록은 명시적 매핑을 통해 옮겨야 합니다. 카카오 ID와 기관 ID가 같은 숫자일 수 있어
값만 보고 자동 추정하지 않습니다. PostgreSQL 16의 `psql`과 아래 수동 도구를 사용합니다.

1. 회원·기관·원본 기록을 포함한 DB 전체를 `pg_dump`로 백업하고, 원본 파일도 보존합니다. 이전이 끝날
   때까지 원본 기록 업로드·조회와 회원 소속 변경을 중단합니다. 이미 숫자가 겹치는 기록이 다른 기관에
   보이는 일을 방지하려면 조회도 차단해야 합니다.
2. 기존 기록 소유자가 새 버전에서 카카오 로그인과 기관 가입을 완료하도록 합니다. 전환 중 로그인·가입은
   허용하되 원본 기록 API는 열지 않습니다. JWT subject가 카카오 ID에서 내부 ID로 바뀌므로 최초 배포 시
   `JWT_SECRET`도 새 키로 교체하여 이전 토큰을 폐기합니다.
3. 백업한 기존 기록 **전체**의 ID·카카오 ID를 새 기관 ID에 연결합니다. 새 버전으로 이미 생성한 기록이
   섞인 DB용 도구가 아니며, 그런 환경에서는 별도로 이전 대상을 분리하는 절차가 필요합니다.
   [CSV 예제](raw-record-owner-mapping.csv.example)를 복사해 실제 값으로 채웁니다.
   헤더는 `raw_record_id,legacy_kakao_id,organization_id` 순서 그대로 사용합니다.
4. CSV를 보관할 작업 디렉터리에서 아래 검증 명령을 실행합니다. `DATABASE_URL`은 운영자가 준비한
   PostgreSQL 접속 문자열이며, 비밀번호는 명령에 직접 넣지 말고 `.pgpass` 등으로 제공합니다.

```bash
# 이 디렉터리의 raw-record-owner-mapping.csv 를 읽습니다.
# /absolute/path/to/backend 는 실제 저장소의 backend 절대 경로로 바꿉니다.
psql "$DATABASE_URL" -f /absolute/path/to/backend/scripts/migrate-raw-record-owners.sql
```

기본 모드는 이전 예정 내역과 건수를 출력한 뒤 `ROLLBACK`합니다. 빈 매핑, 기록 ID 중복·누락,
소유자 불일치, 없는 기록·기관, 비활성 회원이나 다른 기관 소속은 오류로 중단됩니다. 검증 중에도 테이블
잠금을 사용하므로 점검 시간에 실행하세요. 매핑 누락을 정확히 판정하도록 DB의 원본 기록 전체가 CSV에
포함되어야 합니다.

5. 검증 결과를 백업·CSV와 대조한 뒤 같은 디렉터리에서 적용합니다.

```bash
psql "$DATABASE_URL" -v apply=true -f /absolute/path/to/backend/scripts/migrate-raw-record-owners.sql
```

적용은 하나의 트랜잭션에서 `institution_id`만 갱신합니다. 기록 ID·파일 경로·파일 내용·생성 시각은
변경하지 않습니다. 오류가 나면 연결 종료 시 전체 롤백되므로 일부만 이전되지 않습니다. CSV와 실행
결과는 보관하고, 적용 후 같은 CSV를 반복 실행하지 마세요.

6. 기관별 기존 기록 목록·상세 조회와 다른 기관의 접근 거부를 확인한 뒤 원본 기록 API를 다시 엽니다.
   적용 후 문제가 확인되면 API를 계속 중단한 상태에서 사전 백업으로 복구하고 매핑을 다시 검증합니다.
   도구는 서버 시작 시 자동 실행되지 않습니다.
