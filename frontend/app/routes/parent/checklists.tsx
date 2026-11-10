import { CheckCircle2, TriangleAlert } from "lucide-react";
import { useState } from "react";
import { useLoaderData, useRevalidator } from "react-router";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentNotReadyPage } from "@/components/parent/NotReady";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { Busy } from "@/components/ui";
import { ApiError, confirmChecklist, getChecklists, getParentChildren, isReady } from "@/lib/api";
import { formatDateTime, formatMonthDay } from "@/lib/datetime";
import { readSelectedChildId } from "@/lib/selectedChild";
import type { ChecklistItem } from "@/lib/types";

export async function clientLoader() {
  if (!isReady("checklists")) return null;
  const kids = await getParentChildren();
  const childId = readSelectedChildId(kids);
  const childName = kids.find((kid) => kid.id === childId)?.name ?? null;
  return { childId, childName, checklists: await getChecklists(childId) };
}

export default function ChecklistsPageRoute() {
  if (!isReady("checklists")) return <ParentNotReadyPage title="준비물·확인사항" back />;
  return <ChecklistsPage />;
}

function ChecklistsPage() {
  const { childId, childName, checklists } = useLoaderData<typeof clientLoader>()!;
  return <ChecklistsContent key={childId} childId={childId} childName={childName} checklists={checklists} />;
}

function ChecklistsContent({
  childId,
  childName,
  checklists,
}: {
  childId: string;
  childName: string | null;
  checklists: ChecklistItem[];
}) {
  const confirmerLabel = childName ? `${childName} 보호자` : "보호자";
  const revalidator = useRevalidator();
  const [confirmed, setConfirmed] = useState<{ for: ChecklistItem[]; items: Record<string, ChecklistItem> }>({
    for: checklists,
    items: {},
  });
  const overlay = confirmed.for === checklists ? confirmed.items : {};
  const [pendingId, setPendingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function confirm(id: string) {
    if (pendingId) return;
    setPendingId(id);
    setError(null);
    try {
      const result = await confirmChecklist(childId, id);
      setConfirmed((prev) => ({
        for: checklists,
        items: { ...(prev.for === checklists ? prev.items : {}), [id]: result },
      }));
      await revalidator.revalidate();
    } catch (err) {
      if (err instanceof ApiError && err.code === "CHECKLIST_NOT_FOUND") {
        setError("이 요청을 더 이상 확인할 수 없어요.");
        await revalidator.revalidate();
      } else {
        setError("확인하지 못했어요. 다시 시도해 주세요.");
      }
    } finally {
      setPendingId(null);
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader title="준비물·확인사항" back />
      <p className="text-[15px] leading-7 text-ink2">
        기관에서 보낸 준비물과 확인할 것이에요. 보호자 중 한 분이 확인하면 기관에도 확인으로 보여요.
      </p>

      {error ? (
        <p role="alert" className="rounded-2xl bg-blocksoft px-4 py-3 text-[14px] leading-6 text-block">
          {error}
        </p>
      ) : null}

      {checklists.length === 0 ? (
        <div className="flex flex-col items-center gap-2 py-12 text-center">
          <p className="text-[16px] font-bold text-ink">아직 받은 것이 없어요</p>
          <p className="text-[14px] leading-6 text-muted">
            기관이 준비물이나 확인할 것을 보내면
            <br />
            여기에 모여요.
          </p>
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          {checklists.map((raw) => {
            const req = overlay[raw.id] ?? raw;
            const done = req.status === "confirmed";
            return (
              <div
                key={req.id}
                className={`rounded-2xl px-4 py-4 ${
                  done
                    ? "border border-line"
                    : "border border-line bg-humansoft/40"
                }`}
              >
                <div className="mb-2.5 flex items-center gap-2.5">
                  <InstitutionIcon type={req.institution.type} />
                  <span className="min-w-0">
                    <span className="block font-bold">{req.institution.name}</span>
                    <span className="block text-[13px] text-muted">{formatMonthDay(req.sentAt)} 보냄</span>
                  </span>
                  <span
                    className={`ml-auto flex shrink-0 items-center gap-1 rounded-full px-2.5 py-1 text-[12px] font-bold ${
                      done ? "bg-passsoft text-pass" : "bg-humansoft text-human"
                    }`}
                  >
                    {done ? (
                      <CheckCircle2 size={14} strokeWidth={2} />
                    ) : (
                      <TriangleAlert size={14} strokeWidth={2} />
                    )}
                    {done ? "확인 완료" : "확인 필요"}
                  </span>
                </div>
                <ul className="mb-3 flex flex-col gap-1 pl-1 text-[15px] leading-6">
                  {req.items.map((item, i) => (
                    <li key={i} className="flex gap-1.5">
                      <span aria-hidden className="text-muted">
                        ◦
                      </span>
                      {item}
                    </li>
                  ))}
                </ul>
                {done ? (
                  <p className="text-[13px] text-muted tabular-nums">
                    {confirmerLabel}
                    {req.confirmedAt ? ` · ${formatDateTime(req.confirmedAt)}` : ""} 확인
                  </p>
                ) : (
                  <button
                    onClick={() => void confirm(req.id)}
                    disabled={pendingId !== null}
                    className="tap h-12 w-full rounded-2xl bg-ink text-[15px] font-bold text-paper hover:brightness-110 disabled:opacity-60"
                  >
                    {pendingId === req.id ? <Busy>확인 중…</Busy> : "확인했어요"}
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
