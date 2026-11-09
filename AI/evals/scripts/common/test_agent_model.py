import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from common.luna import AGENTS as COMMON_AGENTS, DEFAULT_MODEL, json_format, model  # noqa: E402
from insight import llm as insight_llm  # noqa: E402
from matching import llm as matching_llm  # noqa: E402
from summary import llm as summary_llm  # noqa: E402
from validation import llm as validation_llm  # noqa: E402

AGENTS = ("MATCHING", "VALIDATION", "SUMMARY", "INSIGHT")
LLM = {"matching": matching_llm, "validation": validation_llm, "summary": summary_llm, "insight": insight_llm}


def reset(**env):
    for k in list(os.environ):
        if k == "API_KEY" or k.startswith(AGENTS):
            del os.environ[k]
    os.environ.update(env)


def main() -> int:
    fails = []
    luna = dict(API_URL="http://luna.test", MODEL="gpt-6-luna")
    sonnet = dict(API_URL="http://sonnet.test", MODEL="claude-sonnet-5-5", JSON_MODE="off")
    env = {"API_KEY": "k"}
    for agent, values in (("MATCHING", luna), ("VALIDATION", luna), ("SUMMARY", sonnet), ("INSIGHT", sonnet)):
        env.update({f"{agent}_{k}": v for k, v in values.items()})

    reset(**env)
    for name, module in LLM.items():
        want = "sonnet" if name in ("summary", "insight") else "luna"
        url, key = module._endpoint()
        if not url.startswith(f"http://{want}.test") or key != "k":
            fails.append(f"{name}: 주소/키가 {want} 가 아님 ({url})")
        if model(name) != ("claude-sonnet-5-5" if want == "sonnet" else "gpt-6-luna"):
            fails.append(f"{name}: 모델이 {want} 가 아님")
        if bool(json_format(name)) != (want == "luna"):
            fails.append(f"{name}: JSON 강제가 틀림")

    reset(API_KEY="k", SUMMARY_API_URL="http://x.test")
    if model("summary") != DEFAULT_MODEL or not json_format("summary"):
        fails.append("모델·JSON 강제 기본값이 아님")

    reset(API_KEY="k", SUMMARY_API_URL="http://x.test")
    try:
        insight_llm._endpoint()
        fails.append("INSIGHT_API_URL 이 없는데 에러가 안 남")
    except insight_llm.LlmError as exc:
        if "INSIGHT_API_URL" not in str(exc):
            fails.append(f"에러 메시지에 빠진 변수 이름이 없음: {exc}")
    reset(SUMMARY_API_URL="http://x.test")
    try:
        summary_llm._endpoint()
        fails.append("API_KEY 가 없는데 에러가 안 남")
    except summary_llm.LlmError:
        pass

    deploy = Path(__file__).resolve().parents[4] / "infra/scripts/deploy-ai.sh"
    if deploy.exists():
        text = deploy.read_text(encoding="utf-8")
        missing = [a for a in COMMON_AGENTS if f'"{a.upper()}"' not in text]
        if missing or '"API_KEY"' not in text:
            fails.append(f"deploy-ai.sh 검사에 빠진 에이전트: {missing}")
    else:
        print("SKIP deploy-ai.sh 를 못 찾음 (AI 폴더만 있는 환경)")

    reset()
    for f in fails:
        print("FAIL", f)
    print("\n전부 통과 (4/4)" if not fails else f"\n실패 {len(fails)}건")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
