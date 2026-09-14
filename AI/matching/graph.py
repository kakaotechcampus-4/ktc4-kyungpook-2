"""
매칭 그래프 조립.

    START → extract → shortlist ─┬─(애매)─→ llm_judge ─┐
                                 └─(명확)──────────────┴→ decide → END

조건부 엣지가 이 그래프의 핵심이다.
"명확한 건 LLM 을 부르지 않는다" 는 결정이 코드가 아니라 그래프 구조로 드러난다.
"""

from typing import Literal

from langgraph.graph import END, START, StateGraph

from .config import EXACT_SCORE
from .nodes import decide, extract, llm_judge, shortlist
from .schemas import Candidate, EvidenceSpan, MatchingInput, MatchingOutput
from .state import MatchingState


def route_after_shortlist(state: MatchingState) -> Literal["llm_judge", "decide"]:
    """LLM 을 부를지 말지 결정한다. 호출률이 여기서 정해진다."""
    candidates = state.get("candidates", [])

    # 이름이 그대로 적힌 아이 한 명뿐이고 다른 이름이 없으면 코드로 충분하다.
    # 여기만 모델을 건너뛴다 — 실제 기록의 대부분이 여기에 해당한다.
    if (
        len(candidates) == 1
        and candidates[0]["confidence"] >= EXACT_SCORE
        and not state.get("co_mention")
    ):
        return "decide"

    # 나머지는 전부 모델에 넘긴다.
    #  - 이름이 둘 이상   → 대등 언급인지 확정해야 한다
    #  - 오타 의심·유사 이름 → 어느 쪽인지 판단해야 한다
    #  - 후보가 아예 없음  → 별명·호칭·맥락으로 특정할 수 있는지 물어본다.
    #    여기서 걸러내면 사람이 확인 필요 큐에서 해야 할 일이 그대로 남는다.
    return "llm_judge"


def build_graph():
    builder = StateGraph(MatchingState)

    builder.add_node("extract", extract)
    builder.add_node("shortlist", shortlist)
    builder.add_node("llm_judge", llm_judge)
    builder.add_node("decide", decide)

    builder.add_edge(START, "extract")
    builder.add_edge("extract", "shortlist")
    builder.add_conditional_edges(
        "shortlist",
        route_after_shortlist,
        {"llm_judge": "llm_judge", "decide": "decide"},
    )
    builder.add_edge("llm_judge", "decide")
    builder.add_edge("decide", END)

    return builder.compile()


#: 그래프는 상태를 갖지 않으므로 한 번만 만들어 재사용한다
GRAPH = build_graph()


def run_matching(payload: MatchingInput) -> MatchingOutput:
    """입력 하나를 그래프에 태워 계약대로 된 출력을 돌려준다."""
    final: MatchingState = GRAPH.invoke(
        {
            "journal_entry_id": payload.journal_entry_id,
            "content": payload.content,
            "roster": payload.roster,
            "hint_name": payload.hint_name,
            "hint_birthdate": payload.hint_birthdate,
        }
    )

    status = final["status"]
    candidates = final.get("candidates", [])

    return MatchingOutput(
        journal_entry_id=payload.journal_entry_id,
        status=status,
        matched_child_id=final.get("matched_child_id"),
        confidence=final.get("confidence", 0.0),
        hint_mismatch=final.get("hint_mismatch", False),
        evidence=[EvidenceSpan(**e) for e in final.get("evidence", [])],
        mentioned_child_ids=final.get("mentioned_child_ids", []),
        multi_reason=final.get("multi_reason"),
        # candidates 는 multi 일 때만 채운다 (계약)
        candidates=(
            [Candidate(**c) for c in candidates] if status == "multi" else []
        ),
        llm_called=final.get("llm_called", False),
    )
