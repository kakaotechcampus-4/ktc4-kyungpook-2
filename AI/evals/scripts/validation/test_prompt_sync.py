"""
README 판정 기준과 에이전트 프롬프트가 같은지 검사한다. LLM 호출 없음.

README "판정 기준 > 유형 정의" 표의 각 줄(유형 | 판정 | 정의)이
  - validation/prompts.py 의 ISSUE_DEFINITIONS (정의 문장)
  - validation/config.py 의 ISSUE_LEVEL (BLOCK/REVIEW)
와 글자 하나까지 같아야 통과한다.

README 만 고치고 프롬프트를 안 고쳤거나, 그 반대면 여기서 잡힌다.

실행 (AI 폴더에서):
    python3 evals/scripts/validation/test_prompt_sync.py
"""
import sys
from pathlib import Path

AI_DIR = Path(__file__).parent.parent.parent.parent
sys.path.insert(0, str(AI_DIR))

from validation.config import ISSUE_LEVEL
from validation.prompts import ISSUE_DEFINITIONS

README = Path(__file__).parent / "README.md"


def readme_definitions() -> dict:
    """README 의 '### 유형 정의' 표를 {유형: (판정, 정의)} 로 읽는다."""
    lines = README.read_text(encoding="utf-8").splitlines()
    start = lines.index("### 유형 정의")
    rows = {}
    for line in lines[start + 1:]:
        if line.startswith("### "):
            break
        if not line.startswith("|") or line.startswith("|---") or line.startswith("| 유형"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        issue, level, definition = cells[0], cells[1], "|".join(cells[2:]).strip()
        rows[issue] = (level, definition)
    return rows


def test_prompt_matches_readme():
    readme = readme_definitions()
    problems = []

    for issue in readme.keys() - ISSUE_DEFINITIONS.keys():
        problems.append(f"README 에만 있는 유형: {issue}")
    for issue in ISSUE_DEFINITIONS.keys() - readme.keys():
        problems.append(f"프롬프트에만 있는 유형: {issue}")

    for issue in readme.keys() & ISSUE_DEFINITIONS.keys():
        level, definition = readme[issue]
        if definition != ISSUE_DEFINITIONS[issue]:
            problems.append(f"정의 불일치: {issue}\n    README : {definition}\n    prompts: {ISSUE_DEFINITIONS[issue]}")
        if level != ISSUE_LEVEL.get(issue):
            problems.append(f"판정 불일치: {issue} — README {level}, config {ISSUE_LEVEL.get(issue)}")

    if list(readme) != list(ISSUE_DEFINITIONS):
        problems.append(f"순서 불일치: README {list(readme)} / prompts {list(ISSUE_DEFINITIONS)}")

    assert not problems, "\n".join(problems)


if __name__ == "__main__":
    try:
        test_prompt_matches_readme()
        print(f"[PASS] README 유형 정의 {len(ISSUE_DEFINITIONS)}개와 프롬프트·config 가 일치")
    except AssertionError as e:
        print("[FAIL] README 와 프롬프트가 다름\n" + str(e))
        sys.exit(1)
