# 팀 공통 규칙

백엔드·프론트엔드·AI 구분 없이 이 저장소에서 작업하는 모든 사람과 AI 에이전트에게 적용한다.
영역별 세부 규칙은 각 폴더의 문서를 따른다 (백엔드: [backend/AGENTS.md](backend/AGENTS.md)).

## DB 스키마를 바꾸면 마이그레이션을 더하고 `docs/db/schema.md`를 같은 PR에서 고친다

운영 DB 스키마는 Flyway 마이그레이션(`backend/src/main/resources/db/migration`)만 바꾼다.
[docs/db/schema.md](docs/db/schema.md)는 테이블의 뜻과 규칙을 설명하는 문서다. 노션 문서는 이 파일의 사본이다.

아래 중 하나라도 해당하면 **예외 없이** 같은 PR에서 마이그레이션을 더하고 `docs/db/schema.md`를 고친다.

- 엔티티(`backend/.../domain`) 추가·삭제
- 컬럼 추가·삭제·이름 변경, 타입·길이·NULL 허용 변경
- UNIQUE 등 제약 추가·제거, 인덱스 추가·제거
- enum 값 추가·삭제·이름 변경 (enum 컬럼의 허용 값 검사 제약을 함께 고친다)
- AI가 DB에 직접 쓰는 테이블·컬럼 형식 변경

고치는 방법:

1. 다음 번호로 마이그레이션 파일을 더한다 (`V4__무엇을_바꾸는지.sql`). 이미 머지된 마이그레이션 파일은 고치지 않는다.
2. `./gradlew integrationTest`로 `FlywayMigrationIntegrationTest`를 돌린다. 마이그레이션과 엔티티가 다르면 실패한다.
3. `docs/db/schema.md`의 해당 테이블 설명을 실제 코드와 같게 고친다.
4. 맨 아래 "변경 이력"에 날짜·내용·PR 번호를 한 줄 남긴다.

코드와 문서가 다르면 코드가 맞고 문서가 버그다. 발견한 사람이 고친다.
