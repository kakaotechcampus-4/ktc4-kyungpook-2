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
  // 서버에 아직 없으면 mock 을 불러 진짜 아이 옆에 띄우지 않는다 — 화면은 "준비 중"
  if (!isReady("checklists")) return null;
  const kids = await getParentChildren();
  const childId = readSelectedChildId(kids);
  return { childId, checklists: await getChecklists(childId) };
}

/** 실연동 빌드에서 서버에 아직 없으면 mock 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function ChecklistsPageRoute() {
  if (!isReady("checklists")) return <ParentNotReadyPage title="준비물·확인사항" back />;
  return <ChecklistsPage />;
}

/**
 * P-12 준비물·확인사항 (api-spec G-50 · G-51). 기관이 보낸 준비물과 확인할 것을 보고 "확인했어요" 를 누른다.
 * 이 아이의 보호자 중 한 명이 확인하면 모두에게 확인 완료이고, 되돌리지 않는다. 순서는 서버 순서 그대로다
 * (확인 필요 전부 → 확인 완료 최근 20개).
 */
function ChecklistsPage() {
  const { childId, checklists } = useLoaderData<typeof clientLoader>()!;
  // 아이가 바뀌면(다른 탭에서 고른 아이가 바뀐 뒤 다시 읽는 등) 확인 응답 · 진행 · 오류를 새로 시작한다. 같은 요청을 두 아이가
  // 받으면 id 가 같아서, 그대로 두면 한 아이의 확인이 다른 아이 카드에 보인다
  return <ChecklistsContent key={childId} childId={childId} checklists={checklists} />;
}

function ChecklistsContent({ childId, checklists }: { childId: string; checklists: ChecklistItem[] }) {
  const revalidator = useRevalidator();
  // 확인 응답은 다시 읽기 전까지만 덧씌운다 — for 가 지금 목록이 아니면(다시 읽은 목록이 오면) 서버 값을 쓴다
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
      // 기관이 내렸는지 명부에서 빠졌는지 서버가 같은 404 로 답한다 — 원인을 단정하지 않는다
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
        기관에서 보낸 준비물과 확인할 것입니다. 보호자 중 한 명이 확인하면 기관에도 확인으로 보입니다.
      </p>

      {error ? (
        <p role="alert" className="rounded-2xl bg-blocksoft px-4 py-3 text-[14px] leading-6 text-block">
          {error}
        </p>
      ) : null}

      {checklists.length === 0 ? (
        <p className="py-10 text-center text-[15px] text-muted">받은 준비물·확인사항이 없습니다.</p>
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
                    {req.confirmedByName ?? "다른 보호자"}
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
