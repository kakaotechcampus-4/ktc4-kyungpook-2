import { useState } from "react";
import { useLoaderData, useRevalidator } from "react-router";
import {
  Card,
  EmptyState,
  EvidenceChip,
  FlaggedText,
  Note,
  PageHeader,
  ValidationBadge,
} from "@/components/ui";
import { decideGate1, getGate1Queue } from "@/lib/api";

export async function clientLoader() {
  return { items: await getGate1Queue() };
}

export default function Gate1Page() {
  const { items: queue } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();
  const [editing, setEditing] = useState<string | null>(null);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [rejecting, setRejecting] = useState<string | null>(null);
  const [reasonDrafts, setReasonDrafts] = useState<Record<string, string>>({});

  const pending = queue.filter((s) => s.gate1Status === "pending");
  const rejected = queue.filter((s) => s.gate1Status === "rejected");

  async function approve(id: string, editedContent?: string) {
    await decideGate1(id, { decision: "approve", edited_content: editedContent });
    setEditing(null);
    revalidator.revalidate();
  }

  async function reject(id: string, reason: string) {
    await decideGate1(id, { decision: "reject", reason });
    setRejecting(null);
    revalidator.revalidate();
  }

  return (
    <>
      <PageHeader
        title="1차 검토 · 요약 확인"
        description="등록한 선생님이 요약의 사실 정확성을 승인합니다"
      />

      <div className="flex flex-col gap-5">
        {pending.length === 0 && rejected.length === 0 ? (
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

              {rejecting === s.id ? (
                <div className="mb-4 flex flex-col gap-2">
                  <label className="text-[15px] font-semibold">반려 사유</label>
                  <textarea
                    autoFocus
                    rows={2}
                    value={reasonDrafts[s.id] ?? ""}
                    onChange={(e) =>
                      setReasonDrafts((d) => ({ ...d, [s.id]: e.target.value }))
                    }
                    placeholder="예) 이름이 잘못 매칭된 것 같습니다. 다시 확인해주세요."
                    className="w-full rounded border border-line2 p-3 text-[15px] outline-none focus:border-accent"
                  />
                  <div className="flex gap-2">
                    <button
                      onClick={() => reject(s.id, reasonDrafts[s.id] || "선생님이 반려함")}
                      className="tap rounded bg-block px-4 text-[15px] font-semibold text-white"
                    >
                      반려하기
                    </button>
                    <button
                      onClick={() => setRejecting(null)}
                      className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2"
                    >
                      취소
                    </button>
                  </div>
                </div>
              ) : (
                <div className="mb-3 flex flex-wrap gap-2">
                  <button
                    onClick={() => approve(s.id)}
                    className="tap rounded bg-human px-5 text-[16px] font-semibold text-white hover:brightness-95"
                  >
                    승인
                  </button>
                  <button
                    onClick={() =>
                      editing === s.id ? approve(s.id, drafts[s.id]) : setEditing(s.id)
                    }
                    className="tap rounded border border-human/50 bg-humansoft px-4 text-[16px] font-semibold text-human"
                  >
                    {editing === s.id ? "수정 내용으로 승인" : "수정 후 승인"}
                  </button>
                  <button
                    onClick={() => setRejecting(s.id)}
                    className="tap rounded border border-line2 px-4 text-[16px] font-semibold text-ink2 hover:bg-surface2"
                  >
                    반려
                  </button>
                </div>
              )}

              <Note>
                반려하면 이 요약은 저장되지 않습니다. 등록한 기록으로 다시 작성됩니다.
              </Note>
            </Card>
          );
        })}

        {rejected.map((s) => (
          <Card key={s.id} className="border-block/30 bg-blocksoft/40">
            <div className="mb-3 flex flex-wrap items-center gap-2.5">
              <span className="text-[16px] font-bold">{s.childName}</span>
              <span className="text-[14px] text-muted">
                {s.date.slice(5).replace("-", ".")} {s.recordType}
              </span>
              <span className="ml-auto rounded bg-blocksoft px-2 py-0.5 text-[13px] font-semibold text-block">
                반려됨 · 재작성 대기 중
              </span>
            </div>
            {s.rejectReason ? (
              <p className="mb-3 text-[15px] text-ink2">
                <b className="font-semibold">사유</b> · “{s.rejectReason}”
              </p>
            ) : null}
            <p className="text-[14px] text-muted">
              원본 기록으로부터 요약을 다시 생성하고 있습니다. 완료되면 다시 승인 요청됩니다.
            </p>
          </Card>
        ))}
      </div>
    </>
  );
}
