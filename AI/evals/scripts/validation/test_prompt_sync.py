import sys
from pathlib import Path

AI_DIR = Path(__file__).parent.parent.parent.parent
sys.path.insert(0, str(AI_DIR))

from validation.config import ISSUE_LEVEL, ATTRIBUTION_EXEMPT
from validation.prompts import ISSUE_DEFINITIONS

README = Path(__file__).parent / "README.md"


def readme_table(heading: str) -> list[list[str]]:
    lines = README.read_text(encoding="utf-8").splitlines()
    start = lines.index(heading)
    rows = []
    for line in lines[start + 1:]:
        if line.startswith("### "):
            break
        if not line.startswith("|") or line.startswith("|---") or line.startswith("| 유형"):
            continue
        rows.append([c.strip() for c in line.strip().strip("|").split("|")])
    return rows


def readme_attribution_exempt() -> set:
    return {cells[0] for cells in readme_table("### 근거의 귀속") if cells[1] == "하지 않음"}


def readme_definitions() -> dict:
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

    exempt = readme_attribution_exempt()
    if exempt != set(ATTRIBUTION_EXEMPT):
        problems.append(f"귀속 예외 불일치: README {sorted(exempt)} / config {sorted(ATTRIBUTION_EXEMPT)}")

    assert not problems, "\n".join(problems)


if __name__ == "__main__":
    try:
        test_prompt_matches_readme()
        print(f"[PASS] README 유형 정의 {len(ISSUE_DEFINITIONS)}개, 귀속 예외 {len(ATTRIBUTION_EXEMPT)}개가 프롬프트·config 와 일치")
    except AssertionError as e:
        print("[FAIL] README 와 프롬프트가 다름\n" + str(e))
        sys.exit(1)
