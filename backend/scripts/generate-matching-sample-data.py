#!/usr/bin/env python3
"""
매칭 워커 연동 확인용 가짜 데이터(SQL)를 만든다. 로컬 PostgreSQL 전용이다 — 배포 DB에 넣지 않는다.

AI 팀 채점과 같은 입력을 쓴다 (AI/evals/scripts/matching/build_inputs.py 와 같은 규칙).
  - 명부: 잇다_synthetic_100.json 의 등록 아동 100명 → 확인용 기관 1곳에 전부 연결
  - 기록: 잇다_파이프라인_테스트매니페스트.json 의 matching_test_cases
          케이스마다 raw_record(표지 힌트) 1개 + journal_entry(PENDING) 1개
  - 정답: matching_eval_expected 테이블 (엔티티 아님, 결과 확인용)

같은 확인용 기관(사업자번호 9990000001)으로 넣은 이전 데이터는 지우고 다시 넣는다.
백엔드가 테이블을 만든 뒤(ddl-auto)에 실행한다. 결과는 matching-sample-report.sql 로 본다.

사용법:
  python3 backend/scripts/generate-matching-sample-data.py              # 난이도별 10건
  python3 backend/scripts/generate-matching-sample-data.py --per-type 3
  python3 backend/scripts/generate-matching-sample-data.py --all        # 1,070건
  ... > /tmp/matching-sample.sql
"""

import argparse
import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFESTS = ROOT / "AI" / "evals" / "manifests"
CHILDREN_PATH = MANIFESTS / "잇다_synthetic_100.json"
CASES_PATH = MANIFESTS / "잇다_파이프라인_테스트매니페스트.json"

BUSINESS_NUMBER = "9990000001"
ORG_ID = f"(SELECT id FROM organization WHERE business_number = '{BUSINESS_NUMBER}')"


def q(value):
    """SQL 문자열 리터럴. None 은 NULL."""
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"


def load():
    with open(CHILDREN_PATH, encoding="utf-8") as f:
        children_data = json.load(f)
    with open(CASES_PATH, encoding="utf-8") as f:
        cases = json.load(f)["matching_test_cases"]["cases"]
    children = children_data["dev"] + children_data["holdout"]
    # log_0001~log_0200 은 dev 와 holdout 에 같은 id 가 있다. log_id 만으로 찾으면 200건이 다른 아이의
    # 글로 바뀌므로(build_inputs.py 의 dict 가 그렇다) 아이 id 와 함께 찾는다.
    logs = {(child["child_id"], log["id"]): log for child in children for log in child["일지"]}
    return children, cases, logs


def case_input(case, logs):
    """케이스 → (본문, 기록 날짜, 표지 이름, 표지 생년월일). build_inputs.py 와 같은 규칙."""
    kind = case["난이도"]
    if kind in ("명확", "텍스트혼동_다수아동언급"):
        log = logs[(case["expected_child_id"], case["log_id"])]
        return log["원본텍스트"], log["날짜"], case["expected_이름"], case["expected_생년월일"]
    if kind == "애매_등록자간_유사이름":
        return case["input_설명"], None, case["expected_이름"], case["expected_생년월일"]
    if kind in ("미등록_이름겹침", "미등록_이름안겹침"):
        return case["input_설명"], None, case["input_이름"], case["input_생년월일"]
    raise ValueError(f"unknown case type: {kind}")


def pick(cases, per_type):
    if per_type is None:
        return cases
    by_type = defaultdict(list)
    for case in cases:
        if len(by_type[case["난이도"]]) < per_type:
            by_type[case["난이도"]].append(case)
    return [case for group in by_type.values() for case in group]


def cleanup():
    return f"""
CREATE TABLE IF NOT EXISTS matching_eval_expected (
    journal_entry_id BIGINT PRIMARY KEY,
    case_id TEXT NOT NULL,
    difficulty TEXT NOT NULL,
    expected_manifest_child_id TEXT,
    expected_child_id BIGINT
);
DELETE FROM matching_result WHERE journal_entry_id IN (SELECT journal_entry_id FROM matching_eval_expected);
DELETE FROM journal_entry WHERE id IN (SELECT journal_entry_id FROM matching_eval_expected);
TRUNCATE matching_eval_expected;
DELETE FROM raw_record WHERE institution_id = {ORG_ID}::text;
DELETE FROM child WHERE id IN (SELECT child_id FROM child_organization WHERE organization_id = {ORG_ID});
DELETE FROM child_organization WHERE organization_id = {ORG_ID};
DELETE FROM organization WHERE business_number = '{BUSINESS_NUMBER}';
"""


def roster(children):
    lines = [
        "INSERT INTO organization (name, type, business_number, created_at, updated_at) "
        f"VALUES ('매칭 연동 확인용 기관', 'CENTER', '{BUSINESS_NUMBER}', now(), now());",
        "CREATE TEMP TABLE eval_child (manifest_child_id TEXT PRIMARY KEY, child_id BIGINT NOT NULL);",
    ]
    for child in children:
        lines.append(
            "WITH c AS (INSERT INTO child (name, birthdate, status, created_at, updated_at) "
            f"VALUES ({q(child['이름'])}, {q(child['생년월일'])}, 'ACTIVE', now(), now()) RETURNING id) "
            f"INSERT INTO eval_child SELECT {q(child['child_id'])}, id FROM c;"
        )
    lines.append(
        "INSERT INTO child_organization (child_id, organization_id, created_at, updated_at) "
        f"SELECT child_id, {ORG_ID}, now(), now() FROM eval_child;"
    )
    return "\n".join(lines)


def entries(cases, logs):
    lines = []
    for case in cases:
        content, entry_date, hint_name, hint_birthdate = case_input(case, logs)
        expected = case["expected_child_id"]
        lines.append(
            "WITH r AS (INSERT INTO raw_record (institution_id, original_filename, stored_path, content_type, "
            "size_bytes, status, hint_name, hint_birthdate, created_at, updated_at) "
            f"VALUES ({ORG_ID}::text, {q(case['case_id'] + '.txt')}, {q('eval/' + case['case_id'] + '.txt')}, "
            f"'text/plain', 0, 'PENDING', {q(hint_name)}, {q(hint_birthdate)}, now(), now()) RETURNING id), "
            "j AS (INSERT INTO journal_entry (raw_record_id, entry_date, content, sequence_no, status, "
            "created_at, updated_at) "
            f"SELECT id, {q(entry_date)}::date, {q(content)}, 1, 'PENDING', now(), now() FROM r RETURNING id) "
            "INSERT INTO matching_eval_expected "
            f"SELECT j.id, {q(case['case_id'])}, {q(case['난이도'])}, {q(expected)}, "
            f"(SELECT child_id FROM eval_child WHERE manifest_child_id = {q(expected)}) FROM j;"
        )
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--per-type", type=int, default=10, help="난이도별 케이스 수 (기본 10)")
    group.add_argument("--all", action="store_true", help="1,070건 전부")
    args = parser.parse_args()

    children, cases, logs = load()
    selected = pick(cases, None if args.all else args.per_type)

    print("-- generated by backend/scripts/generate-matching-sample-data.py")
    print(f"-- children={len(children)} cases={len(selected)}")
    # 한 트랜잭션으로 넣어서, 워커가 반쯤 들어간 데이터를 집어 가지 않게 한다.
    print("BEGIN;")
    print(cleanup())
    print(roster(children))
    print(entries(selected, logs))
    print("COMMIT;")


if __name__ == "__main__":
    main()
