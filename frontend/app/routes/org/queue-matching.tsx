import { useState } from "react";
import { Link, useLoaderData, useRevalidator } from "react-router";
import { RosterPicker } from "@/components/org/RosterPicker";
import {
  Card,
  ConfidenceWarning,
  EmptyState,
  EvidenceText,
  Note,
  PageHeader,
} from "@/components/ui";
import { getChildren, getMatchingQueue, resolveMatchingItem } from "@/lib/api";
import type { Child, MatchResolution, MatchingItem } from "@/lib/types";

export async function clientLoader() {
  // 명부를 못 받아도 큐는 띄운다. "이 기관 아동 아님" 은 명부 없이도 처리할 수 있다.
  // 다만 추천 아이 이름도, 직접 고를 목록도 명부에서 오므로 실패했다는 사실은 화면에 알린다.
  const [items, rosterResult] = await Promise.all([
    getMatchingQueue(),
    getChildren().then(
      (roster) => ({ roster, rosterFailed: false }),
      () => ({ roster: [] as Child[], rosterFailed: true }),
    ),
  ]);
  return { items, ...rosterResult };
}

type Candidate = MatchingItem["candidates"][number];

/** 이 아이로 확정할 수 없는 이유 */
type BlockedReason = "not_in_roster" | "pending_consent" | "inactive";

const BLOCKED_LABEL: Record<BlockedReason, string> = {
  not_in_roster: "명부에 없음",
  pending_consent: "보호자 동의 전",
  inactive: "기록 중단 상태",
};

/** 화면에 띄울 아이 한 명. 서버가 준 값이 우선이고, 비어 있으면 명부로 채운다. */
type ResolvedChild = Candidate & {
  /** null 이면 확정할 수 있다 */
  blocked: BlockedReason | null;
};

/**
 * 아이 id 를 명부와 맞춰본다.
 *
 * 서버(O-23 resolve)는 명부에 없거나 동의 전인 아이로는 확정을 거부한다. 그런 아이를
 * 고를 수 있게 두면 버튼을 눌렀을 때 에러만 나므로, 미리 막고 이유를 보여준다.
 * 명부를 못 불러왔으면 판단할 근거가 없어 막지 않는다 — 서버 값만 믿는다.
 */
function resolveChild(
  base: Candidate,
  rosterById: Map<string, Child>,
  rosterFailed: boolean,
): ResolvedChild {
  const r = rosterById.get(base.childId);
  const resolved = {
    ...base,
    name: base.name ?? r?.name ?? null,
    birthDate: base.birthDate ?? r?.birthDate ?? null,
  };
  let blocked: BlockedReason | null = null;
  if (!rosterFailed) {
    if (!r) blocked = "not_in_roster";
    else if (r.status === "pending_consent") blocked = "pending_consent";
    else if (r.status !== "active") blocked = "inactive";
  }
  return { ...resolved, blocked };
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
  if (item.status === "failed") {
    return "AI 판정을 받지 못했습니다";
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
  if (item.status === "failed") {
    return {
      title: "판정 실패",
      message: "시스템 오류로 판정하지 못했습니다. 직접 골라주세요.",
    };
  }
  return {
    title: "확인 필요",
    message: "AI 가 한 명을 지목했지만 확정하지 않았습니다.",
  };
}

export default function MatchingQueuePage() {
  const { items, roster, rosterFailed } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();
  const [index, setIndex] = useState(0);
  const item = items[Math.min(index, items.length - 1)];

  async function resolve(resolution: MatchResolution) {
    if (!item) return;
    // mock 은 items 배열을 제자리에서 고치므로 길이는 호출 전에 읽어둔다
    const remainingAfter = items.length - 1;
    await resolveMatchingItem(item.id, resolution);
    // 처리한 건이 빠지면 같은 자리에 다음 건이 온다. 마지막 건이었으면 한 칸 앞으로 당긴다.
    setIndex((i) => Math.max(0, Math.min(i, remainingAfter - 1)));
    revalidator.revalidate();
  }

  return (
    <>
      <PageHeader
        title="확인이 필요한 기록 · 아이별 정리"
        description="AI가 아이를 확정하지 못한 기록을 사람이 지정합니다"
      />

      {!item ? (
        <EmptyState
          icon="✓"
          title="확인이 필요한 기록이 없습니다"
          description="AI가 확신하지 못한 기록이 생기면 여기로 옵니다."
        />
      ) : (
        <>
          <div className="mb-4 flex items-center justify-between">
            <p className="text-[15px] text-muted tabular-nums">
              남은 기록 <b className="font-semibold text-ink">{items.length}</b>건
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
                onClick={() => setIndex((i) => Math.min(items.length - 1, i + 1))}
                disabled={index >= items.length - 1}
                className="tap rounded border border-line2 px-3 text-[15px] disabled:opacity-40"
              >
                다음
              </button>
            </div>
          </div>

          {rosterFailed ? (
            <div className="mb-4">
              <ConfidenceWarning
                tone="block"
                title="명부 불러오기 실패"
                message="아이 명부를 불러오지 못해 추천 아이를 확인하거나 직접 고를 수 없습니다. 새로고침해 주세요."
              />
            </div>
          ) : null}

          {/* key 로 건이 바뀔 때마다 선택 상태를 초기화한다 */}
          <MatchingCard
            key={item.id}
            item={item}
            roster={roster}
            rosterFailed={rosterFailed}
            onResolve={resolve}
          />
        </>
      )}
    </>
  );
}

function MatchingCard({
  item,
  roster,
  rosterFailed,
  onResolve,
}: {
  item: MatchingItem;
  roster: Child[];
  rosterFailed: boolean;
  onResolve: (resolution: MatchResolution) => Promise<void>;
}) {
  const [selected, setSelected] = useState<string | null>(null);
  /** review 의 "다른 아이", multi 의 "후보에 없는 아이" 를 누르면 명부 검색으로 바뀐다 */
  const [pickingOther, setPickingOther] = useState(false);
  const [pending, setPending] = useState(false);
  const info = notice(item);

  async function submit(resolution: MatchResolution) {
    setPending(true);
    try {
      await onResolve(resolution);
    } finally {
      setPending(false);
    }
  }

  const rosterById = new Map(roster.map((c) => [c.id, c]));

  /*
   * review 의 추천 아이는 matchedChildId 로 온다(AI 계약상 candidates 는 multi 전용).
   * 이름·생년월일은 오지 않아 명부에서 찾는다. 예전 응답 모양도 받도록 candidates[0] 을 뒤에 둔다.
   */
  const suggestedId =
    item.status === "review" ? (item.matchedChildId ?? item.candidates[0]?.childId) : undefined;
  const suggestedResolved = suggestedId
    ? resolveChild(
        item.candidates.find((c) => c.childId === suggestedId) ?? {
          childId: suggestedId,
          name: null,
          birthDate: null,
        },
        rosterById,
        rosterFailed,
      )
    : undefined;
  // 이름도 모르는 아이를 "맞아요" 로 확정하게 둘 수는 없다. 그럴 땐 명부에서 직접 고른다.
  const suggested =
    suggestedResolved && !suggestedResolved.blocked && suggestedResolved.name
      ? suggestedResolved
      : undefined;
  /** review 인데 추천 아이를 보여줄 수 없는 경우 — 처음부터 명부 검색을 띄운다 */
  const suggestionMissing = item.status === "review" && !suggested;

  const candidates =
    item.status === "multi"
      ? item.candidates.map((c) => resolveChild(c, rosterById, rosterFailed))
      : [];

  /** 명부에서 직접 고르는 중인가 */
  const pickingFromRoster =
    item.status === "unmatched" || item.status === "failed" || suggestionMissing || pickingOther;

  return (
    <Card>
      <div className="mb-5 rounded bg-surface2 p-4">
        <p className="mb-1.5 text-[13px] font-semibold tracking-wider text-muted uppercase">
          기록 미리보기
        </p>
        <p className="mb-2 text-[15px] font-semibold">{item.record.fileName}</p>
        <div className="mb-3 text-[16px]">
          {/* AI 가 판정 근거로 인용한 구간을 그대로 표시한다 */}
          <EvidenceText content={item.record.preview ?? ""} spans={item.evidence} />
        </div>
        {item.evidence.length > 0 ? (
          <p className="mb-3 text-[13px] text-muted">
            <span className="mr-1 inline-block size-2.5 rounded-sm bg-accentsoft align-middle" />
            표시된 부분이 AI 가 판단 근거로 삼은 부분입니다
          </p>
        ) : null}
        <RecordMeta item={item} />
      </div>

      <h2 className="mb-3 text-[17px] font-bold">
        {heading(item)}
        {item.status === "multi" ? (
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

      {/* 추천 아이를 보여줄 수 없으면 왜 직접 골라야 하는지 먼저 알린다 */}
      {suggestionMissing ? (
        <div className="mb-4">
          <ConfidenceWarning
            tone="block"
            title="추천 아이 확인 불가"
            message={missingSuggestionMessage(suggestedResolved)}
          />
        </div>
      ) : null}

      {item.status === "multi" && !pickingOther ? (
        <div className="mb-4 flex flex-col gap-2">
          <fieldset className="flex flex-col gap-2">
            <legend className="sr-only">후보 아이 선택</legend>
            {candidates.map((c) => (
              <label
                key={c.childId}
                className={
                  "tap flex items-center gap-3 rounded border border-line2 px-3 has-checked:border-accent has-checked:bg-accentsoft " +
                  (c.blocked ? "cursor-not-allowed opacity-60" : "cursor-pointer hover:border-accent")
                }
              >
                <input
                  type="radio"
                  name={"cand-" + item.id}
                  className="size-4"
                  checked={selected === c.childId}
                  disabled={c.blocked !== null}
                  onChange={() => setSelected(c.childId)}
                />
                <ChildBadge name={c.name} meta={childMeta(c)} />
                {c.blocked ? (
                  <span className="ml-auto text-[13px] font-medium text-block">
                    {BLOCKED_LABEL[c.blocked]}
                  </span>
                ) : null}
              </label>
            ))}
          </fieldset>
          {/* 후보가 전부 틀렸을 수도 있다. 명부에서 직접 고를 길을 열어 둔다. */}
          <button
            onClick={() => {
              setPickingOther(true);
              setSelected(null);
            }}
            className="self-start text-[14px] text-muted underline"
          >
            후보에 없는 아이 고르기
          </button>
        </div>
      ) : null}

      {suggested && !pickingOther ? (
        <div className="mb-4 flex flex-col gap-3">
          <div className="flex items-center gap-3 rounded border border-accent bg-accentsoft/50 px-3 py-2.5">
            <ChildBadge name={suggested.name} meta={childMeta(suggested)} />
            <span className="ml-auto text-[13px] font-medium text-accentink">AI 판정</span>
          </div>
          <div className="flex flex-wrap gap-2">
            <button
              onClick={() => submit({ action: "assign", childId: suggested.childId })}
              disabled={pending}
              className="tap rounded bg-accent px-5 text-[15px] font-semibold text-white hover:bg-accentink disabled:opacity-60"
            >
              맞아요
            </button>
            <button
              onClick={() => setPickingOther(true)}
              className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2"
            >
              다른 아이
            </button>
          </div>
        </div>
      ) : null}

      {pickingFromRoster ? (
        <div className="mb-4 flex flex-col gap-3">
          <RosterPicker
            roster={roster}
            value={selected}
            onChange={setSelected}
            excludeId={pickingOther ? suggested?.childId : undefined}
            name={"roster-" + item.id}
          />

          {pickingOther ? (
            <button
              onClick={() => {
                setPickingOther(false);
                setSelected(null);
              }}
              className="self-start text-[14px] text-muted underline"
            >
              {item.status === "multi" ? "후보 목록으로 돌아가기" : "AI 판정으로 돌아가기"}
            </button>
          ) : null}

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
      ) : null}

      <div className="mb-4">
        <Note>
          AI 가 확정하지 못한 기록은 자동으로 넘어가지 않습니다. 사람이 직접 아이를
          선택해주세요.
        </Note>
      </div>

      <div className="flex flex-wrap gap-2">
        {/* review 의 "맞아요" 는 위에 따로 있다. 여기는 후보·명부에서 고른 아이로 확정하는 버튼이다 */}
        {item.status !== "review" || pickingFromRoster ? (
          <button
            onClick={() => selected && submit({ action: "assign", childId: selected })}
            disabled={!selected || pending}
            className="tap rounded bg-accent px-4 text-[15px] font-semibold text-white hover:bg-accentink disabled:cursor-not-allowed disabled:bg-line2 disabled:text-muted"
          >
            선택한 아이로 확정
          </button>
        ) : null}
        <button
          onClick={() => submit({ action: "not_ours" })}
          disabled={pending}
          className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2 disabled:opacity-60"
        >
          이 기관 아동 아님
        </button>
      </div>
    </Card>
  );
}

/** review 인데 추천 아이를 띄울 수 없을 때, 그 이유. */
function missingSuggestionMessage(resolved: ResolvedChild | undefined): string {
  if (!resolved) return "AI 가 추천한 아이 정보가 오지 않았습니다. 명부에서 직접 골라주세요.";
  const who = resolved.name ? `추천된 아이(${resolved.name})` : "추천된 아이";
  switch (resolved.blocked) {
    case "pending_consent":
      return `${who}는 아직 보호자 동의 전이라 확정할 수 없습니다. 명부에서 직접 골라주세요.`;
    case "inactive":
      return `${who}는 지금 기록을 받을 수 없는 상태입니다. 명부에서 직접 골라주세요.`;
    default:
      // 명부에 없거나, 명부를 못 불러와 이름조차 모르는 경우
      return "추천된 아이를 명부에서 찾지 못했습니다. 명부에서 직접 골라주세요.";
  }
}

/**
 * 유형 · 기록 시각 · 파일 표지. 서버가 아직 안 보내는 값(유형·표지 이름)이나
 * 비어 온 값(날짜)은 칸째 뺀다 — "undefined" 나 1970년이 찍히지 않게.
 */
function RecordMeta({ item }: { item: MatchingItem }) {
  const parts: string[] = [];
  if (item.record.type) parts.push(`유형 · ${item.record.type}`);
  if (item.record.capturedAt) {
    parts.push(
      "기록 시각 · " +
        new Date(item.record.capturedAt).toLocaleString("ko-KR", {
          month: "2-digit",
          day: "2-digit",
          hour: "2-digit",
          minute: "2-digit",
        }),
    );
  }
  if (item.hintName) parts.push(`파일 표지 · ${item.hintName}`);
  if (parts.length === 0) return null;
  return <p className="text-[13px] text-muted">{parts.join("  |  ")}</p>;
}

/** 반 · 생년월일. 서버에 반 정보가 아직 없어서 있는 것만 잇는다. */
function childMeta(c: MatchingItem["candidates"][number]): string {
  return [c.group, c.birthDate].filter(Boolean).join(" · ");
}

function ChildBadge({ name, meta }: { name: string | null; meta: string }) {
  // 명부에서 찾지 못한(삭제된) 아이는 서버가 이름 없이 id 만 보낸다
  const label = name ?? "이름 없음";
  return (
    <>
      <span
        aria-hidden
        className="flex size-8 items-center justify-center rounded-full bg-surface2 text-[14px] font-semibold text-ink2"
      >
        {name ? name.slice(0, 1) : "?"}
      </span>
      <span>
        <span className="block text-[16px] font-semibold">{label}</span>
        {/* 동명이인이면 이름도 반도 같다. 생년월일이 유일한 구분 근거다. */}
        {meta ? <span className="block text-[13px] text-muted tabular-nums">{meta}</span> : null}
      </span>
    </>
  );
}
