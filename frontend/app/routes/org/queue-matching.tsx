import { useState } from "react";
import { Link, useLoaderData, useRevalidator } from "react-router";
import {
  Card,
  ConfidenceWarning,
  EmptyState,
  EvidenceText,
  Note,
  PageHeader,
} from "@/components/ui";
import { getMatchingQueue, resolveMatchingItem } from "@/lib/api";
import type { MatchingItem } from "@/lib/types";

export async function clientLoader() {
  return { items: await getMatchingQueue() };
}

/**
 * 제목과 안내는 **왜 확인이 필요한지**에 따라 갈린다.
 * AI 가 내려주는 multiReason·unmatchedReason 이 서로 다른 상황을 가리키고,
 * 교사가 해야 할 일도 다르기 때문이다 (AI/matching/nodes.py 의 decide 참고).
 */
function heading(item: MatchingItem): string {
  if (item.status === "multi") {
    return item.multiReason === "co_mention"
      ? "여러 아이가 함께 나오는 기록입니다"
      : "어느 아이인지 확인해주세요";
  }
  if (item.status === "unmatched") {
    return item.unmatchedReason === "not_in_roster"
      ? "명부에 없는 이름입니다"
      : "누구의 기록인지 단서가 없습니다";
  }
  return "이 아이가 맞는지 확인해주세요";
}

function notice(item: MatchingItem): { title: string; message: string } {
  if (item.status === "multi") {
    return item.multiReason === "co_mention"
      ? {
          title: "여러 아이",
          message:
            "한 기록에 여러 아이가 대등하게 등장합니다. 대표 아이를 고르거나 기관 아동 아님으로 넘겨주세요.",
        }
      : {
          title: "구별 불가",
          message: "이름만으로는 구별되지 않습니다. 생년월일을 확인하고 선택해주세요.",
        };
  }
  if (item.status === "unmatched") {
    return item.unmatchedReason === "not_in_roster"
      ? {
          title: "미등록 가능성",
          message: "표지의 이름이 명부에 없습니다. 아직 등록하지 않은 아이일 수 있습니다.",
        }
      : {
          title: "근거 없음",
          message:
            "본문과 표지 어디에도 아이를 가리키는 이름이 없어 후보를 만들지 못했습니다.",
        };
  }
  return {
    title: "확인 필요",
    message: "AI 가 한 명을 지목했지만 확정하지 않았습니다.",
  };
}

export default function MatchingQueuePage() {
  const { items } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();
  const [index, setIndex] = useState(0);
  const [selectedChild, setSelectedChild] = useState<string | null>(null);
  const remaining = items;
  const item = remaining[Math.min(index, remaining.length - 1)];

  async function resolve() {
    if (!item) return;
    // TODO: 선택한 childId 를 서버로 보내야 한다. 현재 계약은 id 만 받는다.
    //       "이 기관 아동 아님" 도 같은 함수를 불러서 서버가 둘을 구분하지 못한다.
    await resolveMatchingItem(item.id);
    setSelectedChild(null);
    setIndex(0);
    revalidator.revalidate();
  }

  const info = item ? notice(item) : null;

  return (
    <>
      <PageHeader
        title="확인이 필요한 기록 · 아이별 정리"
        description="AI가 아이를 확정하지 못한 기록을 사람이 지정합니다"
      />

      {!item || !info ? (
        <EmptyState
          icon="✓"
          title="확인이 필요한 기록이 없습니다"
          description="AI가 확신하지 못한 기록이 생기면 여기로 옵니다."
        />
      ) : (
        <>
          <div className="mb-4 flex items-center justify-between">
            <p className="text-[15px] text-muted tabular-nums">
              남은 기록 <b className="font-semibold text-ink">{remaining.length}</b>건
            </p>
            <div className="flex gap-2">
              <button
                onClick={() => setIndex((i) => Math.max(0, i - 1))}
                disabled={index === 0}
                className="tap rounded border border-line2 px-3 text-[15px] disabled:opacity-40"
              >
                이전
              </button>
              <button
                onClick={() => setIndex((i) => Math.min(remaining.length - 1, i + 1))}
                disabled={index >= remaining.length - 1}
                className="tap rounded border border-line2 px-3 text-[15px] disabled:opacity-40"
              >
                다음
              </button>
            </div>
          </div>

          <Card>
            <div className="mb-5 rounded bg-surface2 p-4">
              <p className="mb-1.5 text-[13px] font-semibold tracking-wider text-muted uppercase">
                기록 미리보기
              </p>
              <p className="mb-2 text-[15px] font-semibold">{item.record.fileName}</p>
              <div className="mb-3 text-[16px]">
                {/* AI 가 판정 근거로 인용한 구간을 그대로 표시한다 */}
                <EvidenceText content={item.record.preview} spans={item.evidence} />
              </div>
              {item.evidence.length > 0 ? (
                <p className="mb-3 text-[13px] text-muted">
                  <span className="mr-1 inline-block size-2.5 rounded-sm bg-accentsoft align-middle" />
                  표시된 부분이 AI 가 판단 근거로 삼은 문장입니다
                </p>
              ) : null}
              <p className="text-[13px] text-muted">
                유형 · {item.record.type} &nbsp;|&nbsp; 기록 시각 ·{" "}
                {new Date(item.record.capturedAt).toLocaleString("ko-KR", {
                  month: "2-digit",
                  day: "2-digit",
                  hour: "2-digit",
                  minute: "2-digit",
                })}
              </p>
            </div>

            <h2 className="mb-3 text-[17px] font-bold">
              {heading(item)}
              {item.candidates.length > 0 ? (
                <span className="ml-2 text-[15px] font-medium text-muted">
                  (후보 {item.candidates.length}명)
                </span>
              ) : null}
            </h2>

            <div className="mb-4 flex flex-col gap-2">
              <ConfidenceWarning title={info.title} message={info.message} />

              {/* 표지와 본문이 어긋난 경우. 표지가 틀렸거나 판정이 틀렸거나 둘 중 하나다. */}
              {item.hintMismatch ? (
                <ConfidenceWarning
                  tone="block"
                  title="표지와 다름"
                  message={
                    "표지에는 " +
                    (item.hintName ?? "다른 이름") +
                    " 이라고 적혀 있는데 본문 판정과 다릅니다."
                  }
                />
              ) : null}
            </div>

            {item.candidates.length > 0 ? (
              <fieldset className="mb-4 flex flex-col gap-2">
                <legend className="sr-only">후보 아이 선택</legend>
                {item.candidates.map((c) => (
                  <label
                    key={c.childId}
                    className="tap flex cursor-pointer items-center gap-3 rounded border border-line2 px-3 hover:border-accent has-checked:border-accent has-checked:bg-accentsoft"
                  >
                    <input
                      type="radio"
                      name={"cand-" + item.id}
                      className="size-4"
                      checked={selectedChild === c.childId}
                      onChange={() => setSelectedChild(c.childId)}
                    />
                    <span
                      aria-hidden
                      className="flex size-8 items-center justify-center rounded-full bg-surface2 text-[14px] font-semibold text-ink2"
                    >
                      {c.name.slice(0, 1)}
                    </span>
                    <span>
                      <span className="block text-[16px] font-semibold">{c.name}</span>
                      {/* 동명이인이면 이름도 반도 같다. 생년월일이 유일한 구분 근거다. */}
                      <span className="block text-[13px] text-muted tabular-nums">
                        {c.group} · {c.birthDate}
                      </span>
                    </span>
                  </label>
                ))}
              </fieldset>
            ) : (
              <div className="mb-4 flex flex-col gap-3">
                <label className="flex flex-col gap-1.5">
                  <span className="text-[15px] font-semibold">아이 이름으로 직접 찾기</span>
                  <input
                    placeholder="이름 입력"
                    className="tap rounded border border-line2 px-3 text-[16px] outline-none focus:border-accent"
                  />
                </label>

                {/* 명부에 없는 이름이면 검색해도 나오지 않는다. 등록 화면으로 보낸다. */}
                {item.unmatchedReason === "not_in_roster" ? (
                  <Link
                    to="/children/new"
                    className="tap flex items-center justify-center rounded border border-accent px-4 text-[15px] font-semibold text-accentink hover:bg-accentsoft"
                  >
                    아이 등록하러 가기
                  </Link>
                ) : null}
              </div>
            )}

            <div className="mb-4">
              <Note>
                AI 가 확정하지 못한 기록은 자동으로 넘어가지 않습니다. 사람이 직접 아이를
                선택해주세요.
              </Note>
            </div>

            <div className="flex flex-wrap gap-2">
              <button
                onClick={resolve}
                disabled={item.candidates.length > 0 && !selectedChild}
                className="tap rounded bg-accent px-4 text-[15px] font-semibold text-white hover:bg-accentink disabled:cursor-not-allowed disabled:bg-line2 disabled:text-muted"
              >
                {item.status === "review" ? "맞습니다 · 확정" : "선택한 아이로 확정"}
              </button>
              <button
                onClick={resolve}
                className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2"
              >
                이 기관 아동 아님
              </button>
            </div>
          </Card>
        </>
      )}
    </>
  );
}
