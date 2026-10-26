import { useState } from "react";
import { useLoaderData, useRevalidator } from "react-router";
import { RosterPicker } from "@/components/org/RosterPicker";
import { NotReadyPage } from "@/components/org/NotReady";
import {
  Card,
  EmptyState,
  EvidenceChip,
  FlaggedText,
  Note,
  PageHeader,
  ValidationBadge,
} from "@/components/ui";
import { decideGate1, getChildren, getGate1Queue, isReady, reassignSummaryChild } from "@/lib/api";
import type { ChildListItem, MatchBasis, SummaryItem } from "@/lib/types";

export async function clientLoader() {
  const [items, roster] = await Promise.all([getGate1Queue(), getChildren()]);
  return { items, roster };
}

function basisText(basis: MatchBasis): string {
  if (basis.source === "cover") return `파일 표지: ${basis.name}`;
  if (basis.source === "body") return `본문에 "${basis.name}" 등장`;
  return "선생님이 직접 지정";
}

/**
 * 왜 이 아이로 봤는지 한 줄 + 아동 변경.
 * Gate 1 은 되돌릴 수 있는 단계라 매칭이 틀렸으면 보류하지 않고 여기서 바로 고친다.
 */
function MatchBasisRow({
  item,
  roster,
  onChanged,
}: {
  item: SummaryItem;
  roster: ChildListItem[];
  onChanged: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function apply() {
    if (!selected) return;
    setPending(true);
    try {
      await reassignSummaryChild(item.id, selected);
      setOpen(false);
      setSelected(null);
      onChanged();
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="mb-4">
      <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[14px] text-ink2">
        <span className="font-semibold">매칭 근거</span>
        <span className="text-muted">·</span>
        <span>{item.matchBasis ? basisText(item.matchBasis) : "근거 정보 없음"}</span>
        {!open ? (
          <button
            onClick={() => setOpen(true)}
            className="text-[14px] font-medium text-accentink underline"
          >
            아동 변경
          </button>
        ) : null}
      </p>

      {open ? (
        <div className="mt-3 flex flex-col gap-3 rounded border border-line bg-surface2/50 p-3">
          <RosterPicker
            roster={roster}
            value={selected}
            onChange={setSelected}
            excludeId={item.childId}
            name={"reassign-" + item.id}
          />
          <div className="flex flex-wrap gap-2">
            <button
              onClick={apply}
              disabled={!selected || pending}
              className="tap rounded bg-accent px-4 text-[15px] font-semibold text-white hover:bg-accentink disabled:cursor-not-allowed disabled:bg-line2 disabled:text-muted"
            >
              이 아이로 변경
            </button>
            <button
              onClick={() => {
                setOpen(false);
                setSelected(null);
              }}
              className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2"
            >
              취소
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

/** 백엔드에 아직 없으면 mock 데이터 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function Gate1Route() {
  if (!isReady("gate1")) {
    return (
      <NotReadyPage
        title="1차 검토 · 요약 확인"
        description="등록한 선생님이 요약의 사실 정확성을 승인합니다"
      />
    );
  }
  return <Gate1Page />;
}

function Gate1Page() {
  const { items: queue, roster } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();
  const [editing, setEditing] = useState<string | null>(null);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [holding, setHolding] = useState<string | null>(null);

  const pending = queue.filter((s) => s.gate1Status === "pending");
  const held = queue.filter((s) => s.gate1Status === "hold");

  async function approve(s: SummaryItem, editedContent?: string) {
    await decideGate1(s.id, { decision: "approve", editedContent, version: s.version });
    setEditing(null);
    revalidator.revalidate();
  }

  async function hold(s: SummaryItem) {
    await decideGate1(s.id, { decision: "hold", version: s.version });
    setHolding(null);
    revalidator.revalidate();
  }

  return (
    <>
      <PageHeader
        title="1차 검토 · 요약 확인"
        description="등록한 선생님이 요약의 사실 정확성을 승인합니다"
      />

      <div className="flex flex-col gap-5">
        {pending.length === 0 && held.length === 0 ? (
          <EmptyState
            icon="✓"
            title="승인 대기 중인 요약이 없습니다"
            description="기록을 등록하면 매칭 · 검증 · 요약을 거쳐 여기로 옵니다."
          />
        ) : null}

        {pending.map((s) => {
          const content = drafts[s.id] ?? s.content;
          return (
            <Card key={s.id}>
              <div className="mb-4 flex flex-wrap items-center gap-2.5 border-b border-line pb-3">
                <span
                  aria-hidden
                  className="flex size-8 items-center justify-center rounded-full bg-surface2 text-[14px] font-semibold text-ink2"
                >
                  {s.childName.slice(0, 1)}
                </span>
                <span className="text-[16px] font-bold">{s.childName}</span>
                <span className="text-[14px] text-muted">
                  {s.institutionName} · {s.date.slice(5).replace("-", ".")} {s.recordType}
                </span>
                <span className="ml-auto">
                  <ValidationBadge status={s.validation} />
                </span>
              </div>

              <MatchBasisRow
                item={s}
                roster={roster}
                onChanged={() => revalidator.revalidate()}
              />

              <p className="mb-2 text-[13px] font-semibold tracking-wider text-muted uppercase">
                AI 요약 {editing === s.id ? "(수정 중)" : "(편집 가능)"}
              </p>

              <div className="mb-3">
                <EvidenceChip date={s.date} label={`${s.recordType} 원본 ${s.sourceCount}건`} />
              </div>

              {editing === s.id ? (
                <textarea
                  autoFocus
                  value={content}
                  onChange={(e) => setDrafts((d) => ({ ...d, [s.id]: e.target.value }))}
                  rows={4}
                  className="mb-3 w-full rounded border border-accent p-3 text-[16px] leading-7 outline-none"
                />
              ) : (
                <div className="mb-3 rounded bg-surface2 p-3">
                  <FlaggedText content={content} span={s.flaggedSpan} />
                </div>
              )}

              {s.validation === "REVIEW" && s.flagReason ? (
                <p className="mb-4 rounded border border-review/30 bg-reviewsoft px-3 py-2 text-[15px] text-review">
                  <b className="font-semibold">REVIEW 사유</b> · {s.flagReason}
                </p>
              ) : null}

              {holding === s.id ? (
                <div className="mb-4 flex flex-col gap-2">
                  <p className="text-[15px] font-semibold">이 요약을 보류할까요?</p>
                  <div className="flex gap-2">
                    <button
                      onClick={() => hold(s)}
                      className="tap rounded bg-block px-4 text-[15px] font-semibold text-white"
                    >
                      보류하기
                    </button>
                    <button
                      onClick={() => setHolding(null)}
                      className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2"
                    >
                      취소
                    </button>
                  </div>
                </div>
              ) : (
                <div className="mb-3 flex flex-wrap gap-2">
                  <button
                    onClick={() => approve(s)}
                    className="tap rounded bg-human px-5 text-[16px] font-semibold text-white hover:brightness-95"
                  >
                    승인
                  </button>
                  <button
                    onClick={() =>
                      editing === s.id ? approve(s, drafts[s.id]) : setEditing(s.id)
                    }
                    className="tap rounded border border-human/50 bg-humansoft px-4 text-[16px] font-semibold text-human"
                  >
                    {editing === s.id ? "수정 내용으로 승인" : "수정 후 승인"}
                  </button>
                  <button
                    onClick={() => setHolding(s.id)}
                    className="tap rounded border border-line2 px-4 text-[16px] font-semibold text-ink2 hover:bg-surface2"
                  >
                    보류
                  </button>
                </div>
              )}

              <Note>
                보류하면 보류함으로 옮겨집니다. 보류한 요약은 다시 승인할 수 없고 제거만 할 수 있습니다.
              </Note>
            </Card>
          );
        })}

        {held.map((s) => (
          <Card key={s.id} className="border-block/30 bg-blocksoft/40">
            <div className="mb-3 flex flex-wrap items-center gap-2.5">
              <span className="text-[16px] font-bold">{s.childName}</span>
              <span className="text-[14px] text-muted">
                {s.date.slice(5).replace("-", ".")} {s.recordType}
              </span>
              <span className="ml-auto rounded bg-blocksoft px-2 py-0.5 text-[13px] font-semibold text-block">
                보류됨
              </span>
            </div>
            <p className="text-[14px] text-muted">
              보류한 요약은 보호자에게 공유되지 않습니다. 필요 없으면 제거할 수 있습니다.
            </p>
          </Card>
        ))}
      </div>
    </>
  );
}
