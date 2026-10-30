# AI/evals/scripts/real/run_real.py
"""
실제 일지로 매칭 → 검증 → 요약 → 인사이트를 BE 없이 차례로 돌린다.

    python evals/scripts/real/run_real.py --months 01 --child 아동이름
    python evals/scripts/real/run_real.py --months 01                 # 1월, 전체 아동
    python evals/scripts/real/run_real.py --months 04,05,06 --school synthetic_school_dev.json

⚠️ 데이터는 레포에 없다. 실제 기록(가명)과 가상 학교 기록은 git-ignored 폴더
`evals/generated/real/` 에 따로 받아 둔다 (README 참고).

- 실제 기록: dev.json (센터1). 가상 학교 기록: --school 로 더한다 (학교1).
- 매칭: 명부는 데이터의 아동 전체(생년월일은 가짜). 이용자명 칸을 표지 힌트로 준다.
- 검증: 매칭이 auto/review 로 고른 아이를 대상으로. 못 고르면 정답 아이로 계속 진행하되 기록한다.
- 요약: 아동 × 날짜 × 기관 묶음. BLOCK 일지는 넣지 않는다 (BE 와 같음).
- 인사이트: 아동 × 기간(--window 일, 기본 30). 두 기관 모두 동의한 것으로 둔다.
- 정답표(synthetic_school_answer.json)가 있으면 패턴을 찾았는지, 관계없는 기록을 묶었는지 센다.

결과는 evals/generated/real/runs/ 에 JSON·HTML 로 남는다. 레포에 커밋하지 않는다.
"""
import argparse
import re
import datetime as dt
import json
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

AI_DIR = Path(__file__).resolve().parents[3]
#: 실제 기록·가상 학교 기록·정답표가 있는 곳. git-ignored 라 커밋되지 않는다.
HERE = AI_DIR / "evals" / "generated" / "real"
sys.path.insert(0, str(AI_DIR))

# 에이전트 모듈을 import 하기 전에 .env 를 읽는다 (run_suite.py 와 같음). 늦으면 Luna 설정이 안 보인다.
from dotenv import load_dotenv  # noqa: E402

load_dotenv(AI_DIR / ".env")

from matching.schemas import MatchingInput, RosterEntry  # noqa: E402
from matching.graph import run_matching  # noqa: E402
from validation.schemas import ValidationInput  # noqa: E402
from validation.graph import run_validation  # noqa: E402
from summary.schemas import SummaryInput, SourceEntry  # noqa: E402
from summary.graph import run_summary  # noqa: E402
from insight.schemas import InsightInput  # noqa: E402
from insight.graph import run_insight  # noqa: E402

YEAR = 2025  # 날짜 계산용. 데이터에는 연도를 쓰지 않는다.
INST = {"센터1": (1, "센터"), "학교1": (2, "학교")}


def load(name):
    p = HERE / name
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else []


def retry(fn, tries=3):
    """Luna 가 가끔 빈 응답·시간 초과를 낸다. 같은 입력으로 최대 3번까지 다시 부른다.
    근거가 하나도 안 남은 요약(NoGroundedClaims)은 다시 불러도 같으므로 바로 넘긴다."""
    last = None
    for _ in range(tries):
        try:
            return fn(), None
        except Exception as exc:  # noqa: BLE001
            last = exc
            if type(exc).__name__ == "NoGroundedClaims":
                break
    return None, f"{type(last).__name__}: {last}"


def full_date(mmdd):
    return f"{YEAR}-{mmdd}"


def body(e):
    # BE 업로드와 같은 모양: 비고가 있으면 본문 뒤에 붙인다
    return e["content"] + (f"\n비고: {e['note']}" if e.get("note") else "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--months", default="01", help="쉼표로 구분한 월 (예: 01,02)")
    ap.add_argument("--child", default=None)
    ap.add_argument("--school", default="synthetic_school_demo.json")
    ap.add_argument("--window", type=int, default=30, help="인사이트 기간(일). 30 이면 월 단위")
    args = ap.parse_args()
    months = set(args.months.split(","))

    real = [e for e in load("dev.json") if e["date"][:2] in months]
    school = [e for e in load(args.school) if e["date"][:2] in months]
    entries = real + school
    if args.child:
        entries = [e for e in entries if e["child"] == args.child]
    entries.sort(key=lambda e: (e["date"], e["id"]))
    if not entries:
        sys.exit("해당 조건의 일지가 없습니다")

    names = sorted({e["child"] for e in load("dev.json")} | {e["child"] for e in school})
    child_id = {n: i + 1 for i, n in enumerate(names)}
    roster = [RosterEntry(child_id=child_id[n], name=n, birthdate=f"2015-01-{i + 1:02d}") for i, n in enumerate(names)]
    jid = {e["id"]: i + 1 for i, e in enumerate(entries)}
    log = {"args": vars(args), "entries": [], "summaries": [], "insights": []}
    calls = Counter()
    t0 = time.time()

    # ── 1·2. 매칭 → 검증 ──
    for e in entries:
        content = body(e)
        m = run_matching(MatchingInput(journal_entry_id=jid[e["id"]], content=content, roster=roster,
                                       entry_date=full_date(e["date"]), hint_name=e["child"]))
        calls["matching"] += 1
        expect = child_id[e["child"]]
        got = m.matched_child_id if m.status in ("auto", "review") else None
        subject = got or expect
        v = run_validation(ValidationInput(journal_entry_id=jid[e["id"]], content=content,
                                           subject_child_id=subject, subject_name=names[subject - 1]))
        calls["validation"] += 1
        mentioned = [names[i - 1] for i in m.mentioned_child_ids if i != expect]
        log["entries"].append({
            "id": e["id"], "source": e.get("source", "real"), "child": e["child"],
            "matching": m.status, "matching_ok": got == expect, "llm_called": m.llm_called,
            "verdict": v.verdict, "issue_types": v.issue_types,
            "issues": [{"type": i.type, "text": content[i.start:i.end] if i.start is not None else None} for i in v.issues],
            "mentioned_other": mentioned,
        })
        print(f"[{e['id']}] {e['child']} 매칭={m.status}{'' if got == expect else '(X)'} 검증={v.verdict} {v.issue_types}")

    # ── 3. 요약 (아동 × 날짜 × 기관) ──
    verdict = {r["id"]: r for r in log["entries"]}
    groups = defaultdict(list)
    for e in entries:
        if verdict[e["id"]]["verdict"] == "BLOCK":
            continue
        groups[(e["child"], e["date"], e["institution"])].append(e)

    claims_by_child = defaultdict(list)
    for sid, ((child, date, inst), es) in enumerate(sorted(groups.items()), start=1):
        inst_id, inst_type = INST[inst]
        others = sorted({n for x in es for n in verdict[x["id"]]["mentioned_other"]})
        rec = {"summary_id": sid, "child": child, "date": date, "institution": inst, "entry_ids": [x["id"] for x in es]}
        payload = SummaryInput(
            child_id=child_id[child], child_name=child, entry_date=full_date(date),
            institution_id=inst_id, institution_name=inst,
            sources=[SourceEntry(journal_entry_id=jid[x["id"]], content=body(x), entry_date=full_date(date)) for x in es],
            other_child_names=others)
        out, err = retry(lambda: run_summary(payload))
        calls["summary"] += 1
        if out:
            rec.update(ok=True, content=out.content, n_claims=len(out.claims), needs_review=out.needs_review,
                       review_reasons=out.review_reasons, uncovered=out.uncovered_entry_ids,
                       no_content=getattr(out, "no_content", False))
            for k, c in enumerate(out.claims):
                claims_by_child[child].append({
                    "claim_id": f"{sid}-{k}", "text": c.text, "entry_date": full_date(date),
                    "institution_id": inst_id, "institution_type": inst_type, "source": "AI",
                    "evidence": [{"journal_entry_id": ev.journal_entry_id, "quote": ev.quote} for ev in c.evidence],
                })
        else:
            rec.update(ok=False, error=err)
        log["summaries"].append(rec)
        print(f"[요약 {sid}] {child} {date} {inst} → {'claims ' + str(rec.get('n_claims')) if rec.get('ok') else rec['error'][:60]}")

    # ── 4. 인사이트 (아동 × 기간) ──
    consented = [{"institution_id": i, "institution_type": t} for i, t in INST.values()]
    for child, claims in sorted(claims_by_child.items()):
        windows = defaultdict(list)
        for c in claims:
            d = dt.date.fromisoformat(c["entry_date"])
            key = d.month if args.window >= 28 else d.isocalendar()[1]
            windows[key].append(c)
        for key, cs in sorted(windows.items()):
            days = sorted(c["entry_date"] for c in cs)
            if len(cs) < 2:
                continue
            payload = InsightInput(child_id=child_id[child], child_name=child, period_from=days[0],
                                   period_to=days[-1], claims=cs, consented_institutions=consented)
            out, err = retry(lambda: run_insight(payload))
            calls["insight"] += 1
            if not out:
                log.setdefault("insight_errors", []).append({"child": child, "window": key, "error": err})
                print(f"[인사이트] {child} {key} claims={len(cs)} → 실패 {err[:120]}")
                continue
            for ins in out.insights:
                src = [e["id"] for e in entries if jid[e["id"]] in set(ins.source_entry_ids)]
                log["insights"].append({"child": child, "window": key, **ins.model_dump(), "source_diary_ids": src})
            print(f"[인사이트] {child} {key} claims={len(cs)} → {len(out.insights)}개")

    # ── 리포트 ──
    E = log["entries"]
    print("\n===== 요약 리포트 =====")
    print(f"일지 {len(E)}건 (실제 {sum(r['source'] == 'real' for r in E)} · 가상 {sum(r['source'] != 'real' for r in E)})")
    print(f"매칭  {Counter(r['matching'] for r in E)}  정답 {sum(r['matching_ok'] for r in E)}/{len(E)}")
    print(f"검증  {Counter(r['verdict'] for r in E)}")
    print(f"      사유 {Counter(t for r in E for t in r['issue_types'])}")
    S = log["summaries"]
    print(f"요약  {sum(s['ok'] for s in S)}/{len(S)} 성공, claims {sum(s.get('n_claims', 0) for s in S)}, "
          f"needs_review {sum(s.get('needs_review', False) for s in S)}")
    print(f"인사이트 {len(log['insights'])}개 (기관 간 {sum(i['cross_institution'] for i in log['insights'])})")
    for i in log["insights"]:
        print(f"  - [{i['child']}] {i['content']}  ← {i['source_diary_ids']}")

    log["compare"] = compare(log, entries)
    c = log["compare"]
    print("── v1 비교 ──")
    print(f"  요약할 내용 없음(no_content)  {c['no_content']}건 {c['no_content_groups']}")
    print(f"  하루 기록 하나로 만든 인사이트  {c['single_record_insights']}/{len(log['insights'])}  (v1 4/17)")
    print(f"  검증 사유  건강관리기록 {c['issue_health']} · 진단명 {c['issue_diagnosis']}  (v1 진단명 BLOCK 8, 일상 건강 6)")
    print(f"  '~니다' 말투  요약 {c['polite_summaries']}/{c['ok_summaries']} · 인사이트 {c['polite_insights']}/{len(log['insights'])}  (v1 요약 6/60)")

    answer = load("synthetic_school_answer.json")
    if answer:
        used = {d for i in log["insights"] for d in i["source_diary_ids"]}
        ids = {e["id"] for e in entries}
        pats = [p for p in answer["patterns"] if set(p["claims"]) <= ids]
        hit = [p["id"] for p in pats if len(set(p["claims"]) & used) >= 2 and any(
            len(set(p["claims"]) & set(i["source_diary_ids"])) >= 2 for i in log["insights"])]
        bad = sorted(set(answer["distractors"]["ids"]) & used & ids)
        print(f"정답표  패턴 {len(hit)}/{len(pats)} 찾음 {hit}  ·  관계없는 기록 묶음 {len(bad)}건 {bad}")
        log["answer"] = {"hit": hit, "total": [p["id"] for p in pats], "distractor_used": bad}

    print(f"호출 {dict(calls)} · {time.time() - t0:.0f}초")
    out_dir = HERE / "runs"
    out_dir.mkdir(exist_ok=True)
    name = f"run_{args.months.replace(',', '-')}_{args.child or 'all'}_{dt.datetime.now():%m%d_%H%M}.json"
    (out_dir / name).write_text(json.dumps(log, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"저장: {out_dir / name}")
    html_path = (out_dir / name).with_suffix(".html")
    html_path.write_text(render_html(log, entries), encoding="utf-8")
    print(f"보고서: {html_path}  ← 브라우저로 열어 보세요")


#: 문장이 합쇼체로 끝나는가. "했습니다." "입니다." "됩니다." 를 잡는다.
POLITE_END = re.compile(r"니다[.!]?\s*$")


def _polite(text):
    return any(POLITE_END.search(s) for s in re.split(r"(?<=[.!?])\s+", text or "") if s.strip())


def compare(log, entries):
    """v1 (2026-10-28) 에서 고친 것들이 효과가 있었는지 보는 숫자. 판정에는 쓰지 않는다."""
    by_id = {e["id"]: e for e in entries}
    S = [s for s in log["summaries"] if s.get("ok")]
    nc = [s for s in S if s.get("no_content")]
    single = sum(1 for i in log["insights"]
                 if len({(by_id[d]["date"], by_id[d]["institution"]) for d in i["source_diary_ids"]}) <= 1)
    issues = Counter(t for r in log["entries"] for t in r["issue_types"])
    return {
        "no_content": len(nc),
        "no_content_groups": [f"{s['date']} {s['institution']}" for s in nc],
        "single_record_insights": single,
        "issue_health": issues.get("건강관리기록", 0),
        "issue_diagnosis": issues.get("진단명", 0),
        "ok_summaries": len(S),
        "polite_summaries": sum(_polite(s.get("content")) for s in S),
        "polite_insights": sum(_polite(i.get("content")) for i in log["insights"]),
    }


def render_html(log, entries):
    import html as H
    by_id = {e["id"]: e for e in entries}
    esc = lambda x: H.escape(str(x))
    tag = lambda e: "가상 학교" if e.get("source") == "synthetic" else "실제 센터"
    color = {"PASS": "#2e7d32", "REVIEW": "#b26a00", "BLOCK": "#c62828"}
    rows = []
    for r in log["entries"]:
        e = by_id[r["id"]]
        why = ", ".join(f"{i['type']}" + (f" (“{esc(i['text'])}”)" if i["text"] else "") for i in r["issues"]) or "-"
        rows.append(f"<tr><td>{esc(e['date'])}</td><td>{tag(e)}</td><td>{esc(r['id'])}</td>"
                    f"<td>{esc(r['matching'])}{'' if r['matching_ok'] else ' ❌'}</td>"
                    f"<td style='color:{color[r['verdict']]};font-weight:600'>{r['verdict']}</td><td>{why}</td>"
                    f"<td class='t'>{esc(e['content'][:140])}…</td></tr>")
    sums = []
    for s_ in log["summaries"]:
        body_ = esc(s_["content"]) if s_.get("ok") else f"<span style='color:#c62828'>실패 — {esc(s_['error'][:200])}</span>"
        sums.append(f"<tr><td>{esc(s_['date'])}</td><td>{esc(s_['institution'])}</td><td>{s_.get('n_claims', '-')}</td>"
                    f"<td class='t'>{body_}</td></tr>")
    ins = []
    for i in log["insights"]:
        sup = "".join(f"<li>{esc(x['action'])} → {esc(x['result'])}</li>" for x in i["supports"]) or "<li>-</li>"
        src = ", ".join(f"{d} ({tag(by_id[d])})" for d in i["source_diary_ids"])
        warn = " ⚠️ 교사 작성 포함" if i.get("teacher_claim_ids") else ""
        ins.append(f"<div class='card'><b>{esc(i['child'])}</b> · {'기관 간 차이' if i['cross_institution'] else '한 기관'}{warn}"
                   f"<p class='big'>{esc(i['content'])}</p><p><b>상황</b> {esc(i['situation'])}<br><b>반응</b> {esc(i['behavior'])}</p>"
                   f"<b>대처 → 결과</b><ul>{sup}</ul><p class='src'>근거 일지: {src}</p></div>")
    for x in log.get("insight_errors", []):
        ins.append(f"<div class='card' style='border-color:#c62828'>{esc(x['child'])} {x['window']} 인사이트 실패 — {esc(x['error'][:300])}</div>")
    ans = log.get("answer")
    ans_html = (f"<p>정답 패턴 <b>{len(ans['hit'])}/{len(ans['total'])}</b> 찾음 {esc(ans['hit'])} · 놓친 것 "
                f"{esc(sorted(set(ans['total']) - set(ans['hit'])))} · 관계없는 기록을 묶은 것 <b>{len(ans['distractor_used'])}</b>건 "
                f"{esc(ans['distractor_used'])}</p>") if ans else "<p>정답표 없음</p>"
    E = log["entries"]
    head = (f"일지 {len(E)}건 · 매칭 정답 {sum(r['matching_ok'] for r in E)}/{len(E)} · "
            f"검증 {dict(Counter(r['verdict'] for r in E))} · 요약 {sum(s_['ok'] for s_ in log['summaries'])}/{len(log['summaries'])} 성공 · "
            f"인사이트 {len(log['insights'])}개")
    c = log.get("compare")
    if c:
        head += (f"<br>v1 비교 — 요약할 내용 없음 {c['no_content']}건 · 하루 기록 인사이트 {c['single_record_insights']}/{len(log['insights'])} · "
                 f"건강관리기록 {c['issue_health']} · 진단명 {c['issue_diagnosis']} · "
                 f"'~니다' 말투 요약 {c['polite_summaries']}/{c['ok_summaries']}, 인사이트 {c['polite_insights']}/{len(log['insights'])}")
    return f"""<!doctype html><meta charset="utf-8"><title>실데이터 점검</title>
<style>body{{font-family:-apple-system,sans-serif;margin:24px;max-width:1100px;color:#222}}table{{border-collapse:collapse;width:100%;margin:8px 0 24px}}
td,th{{border:1px solid #ddd;padding:6px 8px;font-size:13px;vertical-align:top}}th{{background:#f5f5f5}}.t{{color:#555}}
.card{{border:1px solid #ccc;border-radius:8px;padding:12px 16px;margin:10px 0}}.big{{font-size:16px;margin:8px 0}}.src{{color:#666;font-size:12px}}</style>
<h1>실데이터 점검 — {esc(log['args'])}</h1><p><b>{head}</b></p>
<h2>1. 일지별 매칭·검증</h2><table><tr><th>날짜</th><th>출처</th><th>id</th><th>매칭</th><th>검증</th><th>사유 (가리킨 곳)</th><th>본문 앞부분</th></tr>{''.join(rows)}</table>
<h2>2. 요약 (아이 × 날짜 × 기관)</h2><table><tr><th>날짜</th><th>기관</th><th>문장 수</th><th>요약</th></tr>{''.join(sums)}</table>
<h2>3. 인사이트</h2>{''.join(ins) or '<p>없음</p>'}
<h2>4. 정답표 채점</h2>{ans_html}"""


if __name__ == "__main__":
    main()
