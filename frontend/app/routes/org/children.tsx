import { useCallback, useEffect, useMemo, useState, type MouseEvent } from "react";
import { skipSearchOnlyChange } from "@/lib/revalidate";
import { Plus } from "lucide-react";
import { useLoaderData, useRevalidator, useSearchParams } from "react-router";
import { ChildPopup, guardianChip, type ChildTab } from "@/components/org/ChildPopup";
import { LinkRequestCard } from "@/components/org/LinkRequestCard";
import { RegisterChildPopup } from "@/components/org/RegisterChildPopup";
import { Chip, EmptyLine, Toast, WorkHead, WorkRow } from "@/components/org/WorkList";
import { useWalk } from "@/components/ui/ListPopup";
import { Pager, usePage } from "@/components/ui/Pager";
import { getChildren, getInbox, getInsights, getLinkRequests, getMyInstitution, loadIfReady } from "@/lib/api";
import { formatInviteCode } from "@/lib/invite";
import type { ChildListItem, ConsentState, Insight } from "@/lib/types";

/**
 * 아동 목록 — 옛 인사이트 승인(O-40 · O-42) · 관찰·분석 결과 · 메시지(수신함 O-50 · O-51)를 모은 화면.
 * 칸 셋: 아이(명부) · 분석 승인(우리가 승인할 차례) · 연결 요청(O-60).
 * 아이 카드를 누르면 아이 팝업(분석 · 기록 · 보호자)이 뜬다. 분석 승인은 그 팝업에서 하고, 승인하면 다음 분석이 이어서 뜬다.
 *
 * 주소: `?tab=kids|approve|req`, `&open=first` (분석 승인 첫 건) · `&open=insight&id=` (그 분석),
 * `?register=1` (등록 팝업), `?child=<id>` (그 아이 팝업, 옛 /children/:id).
 */
/** 칸 바꾸기 · `?open=` 지우기로는 다시 불러오지 않는다 (lib/revalidate) */
export const shouldRevalidate = skipSearchOnlyChange;

export async function clientLoader() {
  const [children, institution, linkRequests, pending, approved, inbox] = await Promise.all([
    getChildren(),
    loadIfReady("institutionInfo", getMyInstitution),
    loadIfReady("linkRequests", getLinkRequests),
    loadIfReady("insights", () => getInsights("pending")),
    loadIfReady("insights", () => getInsights("approved")),
    loadIfReady("inbox", getInbox),
  ]);
  return {
    children,
    institution,
    linkRequests: linkRequests ?? [],
    pending: pending ?? [],
    approved: approved ?? [],
    inbox: inbox ?? [],
  };
}

type Tab = "kids" | "approve" | "req";
const idOf = (i: Insight) => i.id;

/** 아이 칸 거르기 — 칩 문구(guardianChip)와 같은 말을 쓴다 */
const CONSENT_FILTERS: { key: ConsentState | "all"; label: string }[] = [
  { key: "all", label: "전체" },
  { key: "granted", label: "동의함" },
  { key: "not_granted", label: "보호자 동의 전" },
  { key: "revoked", label: "동의 철회" },
];

export default function ChildrenPage() {
  const { children, institution, linkRequests, pending, approved, inbox } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();
  const [params, setParams] = useSearchParams();
  const tabParam = params.get("tab");
  const tab: Tab = tabParam === "approve" || tabParam === "req" ? tabParam : "kids";
  const [toast, setToast] = useState<string | null>(null);
  const clearToast = useCallback(() => setToast(null), []);
  const [registering, setRegistering] = useState(false);
  /** 아동 칸에서 연 아이 팝업 */
  const [openKid, setOpenKid] = useState<{ id: string; tab: ChildTab; unread: boolean } | null>(null);
  const [outcomes, setOutcomes] = useState<Record<string, string>>({});

  const walk = useWalk(pending, idOf);
  const unreadBy = useMemo(() => countBy(inbox.filter((m) => !m.readAt), (m) => m.childId), [inbox]);
  const pendingBy = useMemo(() => countBy(pending, (i) => i.childId), [pending]);

  // 할 일이 있는 아이(승인할 분석·새 분석)를 위로, 나머지는 명부 순서 그대로
  const kids = useMemo(
    () =>
      [...children].sort(
        (a, b) => Number(!!(pendingBy[b.id] || unreadBy[b.id])) - Number(!!(pendingBy[a.id] || unreadBy[a.id])),
      ),
    [children, pendingBy, unreadBy],
  );
  const [query, setQuery] = useState("");
  const [consentFilter, setConsentFilter] = useState<ConsentState | "all">("all");
  const shownKids = useMemo(() => {
    const q = query.trim().toLowerCase();
    return kids.filter(
      (c) =>
        (consentFilter === "all" || c.consent === consentFilter) &&
        (!q || c.name.toLowerCase().includes(q) || (c.externalId ?? "").toLowerCase().includes(q)),
    );
  }, [kids, query, consentFilter]);
  const kidPage = usePage(shownKids);
  const apPage = usePage(walk.visible);
  const reqPage = usePage(linkRequests);

  function setTab(next: Tab) {
    setParams(next === "kids" ? {} : { tab: next }, { replace: true, preventScrollReset: true });
  }

  // 주소로 들어온 바로가기를 한 번 처리하고 지운다
  useEffect(() => {
    const open = params.get("open");
    const child = params.get("child");
    const register = params.get("register");
    if (!open && !child && !register) return;
    if (register) setRegistering(true);
    if (child && children.some((c) => c.id === child)) setOpenKid({ id: child, tab: "analysis", unread: false });
    if (open && tab === "approve") {
      const id = params.get("id");
      // id 를 줬는데 없으면(보류·승인됨) 다른 아이 분석으로 대신 열지 않는다
      const target = open === "insight" && id ? pending.find((i) => i.id === id) : pending[0];
      if (target) walk.open(target.id);
    }
    setParams(tab === "kids" ? {} : { tab }, { replace: true });
  }, [params]);

  function refreshSoon() {
    setTimeout(() => revalidator.revalidate(), 2100);
  }

  function decided(insight: Insight, decision: "approve" | "hold", fromWalk: boolean) {
    const text = `${insight.childName} ${insight.period} 분석을 ${decision === "approve" ? "승인했어요" : "보류했어요"}`;
    if (fromWalk) {
      setOutcomes((o) => ({ ...o, [insight.id]: text }));
      walk.done(insight.id);
      refreshSoon();
    } else {
      setOpenKid(null);
      setToast(text);
      revalidator.revalidate();
    }
  }

  function stale(message: string) {
    walk.close();
    setOpenKid(null);
    setToast(message);
    revalidator.revalidate();
  }

  const unreadAll = inbox.filter((m) => !m.readAt).length;
  const approveLeft = walk.visible.filter((i) => !walk.isDone(i.id)).length;
  const tabs: { key: Tab; label: string; count: number; hot: boolean; dot?: boolean }[] = [
    { key: "kids", label: "아이", count: children.length, hot: false, dot: unreadAll > 0 },
    { key: "approve", label: "분석 승인", count: approveLeft, hot: approveLeft > 0 },
    { key: "req", label: "연결 요청", count: linkRequests.length, hot: linkRequests.length > 0 },
  ];

  const walkChild = walk.current ? childOf(walk.current, children) : null;
  const kidChild = openKid ? children.find((c) => c.id === openKid.id) : undefined;

  return (
    <div>
      <WorkHead
        title="아동 목록"
        tour="kids"
        right={
          <div className="flex flex-wrap items-center gap-2">
            {institution ? <OrgCode code={institution.code} /> : null}
            <button
              type="button"
              data-tour="register"
              onClick={() => setRegistering(true)}
              className="tap inline-flex items-center gap-1.5 rounded-full bg-ink px-5 text-[15px] font-bold text-paper hover:brightness-110"
            >
              <Plus aria-hidden size={16} strokeWidth={2.4} />
              아이 등록
            </button>
          </div>
        }
      />

      <div data-tour="tabs" role="tablist" aria-label="아동 목록 나눠 보기" className="mb-4 flex w-fit rounded-full bg-surface2 p-1">
        {tabs.map((t) => (
          <button
            key={t.key}
            type="button"
            role="tab"
            aria-selected={tab === t.key}
            onClick={() => setTab(t.key)}
            className={`flex items-center gap-1.5 rounded-full px-4 py-1.5 text-[15px] font-bold ${
              tab === t.key ? "bg-surface text-ink shadow-sm" : "text-ink2"
            }`}
          >
            {t.label}
            {t.key === "kids" || t.count ? (
              <span className={`rounded-full px-1.5 text-[13px] tabular-nums ${t.hot ? "bg-mark text-markink" : "text-muted"}`}>{t.count}</span>
            ) : null}
            {t.dot ? <span aria-label="안 읽은 분석 있음" className="size-2 rounded-full bg-block" /> : null}
          </button>
        ))}
      </div>

      {tab === "kids" ? (
        children.length === 0 ? (
          <EmptyLine>아직 등록한 아이가 없어요</EmptyLine>
        ) : (
          <>
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <input
                id="kid-search"
                type="search"
                value={query}
                onChange={(e) => {
                  setQuery(e.target.value);
                  kidPage.setPage(1);
                }}
                placeholder="이름 또는 관리번호"
                aria-label="이름 또는 관리번호로 찾기"
                className="tap w-full max-w-[280px] rounded-full border border-line2 bg-surface px-4 text-[15px] outline-none focus:border-accent"
              />
              <div role="group" aria-label="동의 상태로 거르기" className="flex flex-wrap gap-1.5">
                {CONSENT_FILTERS.map((f) => (
                  <button
                    key={f.key}
                    type="button"
                    aria-pressed={consentFilter === f.key}
                    onClick={() => {
                      setConsentFilter(f.key);
                      kidPage.setPage(1);
                    }}
                    className={`rounded-full px-3 py-1 text-[14px] font-bold ${
                      consentFilter === f.key ? "bg-ink text-paper" : "bg-surface2 text-ink2 hover:brightness-95"
                    }`}
                  >
                    {f.label}
                  </button>
                ))}
              </div>
            </div>
            {shownKids.length === 0 ? (
              <EmptyLine>조건에 맞는 아이가 없어요</EmptyLine>
            ) : (
              <>
                <div data-tour="kids" className="grid gap-2.5">
                  {kidPage.items.map((c) => (
                    <KidCard
                      key={c.id}
                      child={c}
                      unread={unreadBy[c.id] ?? 0}
                      onOpen={(t, unread) => setOpenKid({ id: c.id, tab: t, unread })}
                    />
                  ))}
                </div>
                <Pager {...kidPage} unit="명" onChange={kidPage.setPage} />
              </>
            )}
          </>
        )
      ) : tab === "approve" ? (
        walk.visible.length === 0 ? (
          <EmptyLine>승인할 분석이 없어요</EmptyLine>
        ) : (
          <>
            <div className="grid gap-2.5">
              {apPage.items.map((i) => (
                <WorkRow
                  key={i.id}
                  who={i.childName}
                  sub={`${i.period} · 기록 ${i.evidence.length}건`}
                  text={i.content}
                  tag={
                    <span className="flex flex-wrap justify-end gap-1.5">
                      {i.teacherWritten ? <Chip tone="human">교사 작성 포함</Chip> : <Chip tone="human">분석 도착</Chip>}
                      {/* #388 지난 기록을 찾아 쓴 분석. 경고가 아니라 알아 둘 일이라 ai 색 */}
                      {i.evidence.some((e) => e.lookedUp) ? <Chip tone="ai">지난 기록 포함</Chip> : null}
                    </span>
                  }
                  action="읽고 승인"
                  onOpen={() => walk.open(i.id)}
                  done={walk.isDone(i.id) ? (outcomes[i.id] ?? "처리했어요") : undefined}
                />
              ))}
            </div>
            <Pager {...apPage} onChange={apPage.setPage} />
          </>
        )
      ) : linkRequests.length === 0 ? (
        <EmptyLine>기다리는 연결 요청이 없어요</EmptyLine>
      ) : (
        <>
          <div className="grid gap-3">
            {reqPage.items.map((r) => (
              <LinkRequestCard key={r.child.id} request={r} />
            ))}
          </div>
          <Pager {...reqPage} onChange={reqPage.setPage} />
        </>
      )}

      {walk.current && walkChild ? (
        <ChildPopup
          key={`walk-${walk.current.id}`}
          child={walkChild}
          review={walk.current}
          approved={approved.filter((i) => i.childId === walkChild.id)}
          inbox={inbox.filter((m) => m.childId === walkChild.id)}
          position={walk.position}
          onClose={walk.close}
          onDecided={(insight, d) => decided(insight, d, true)}
          onStale={stale}
          onRead={() => revalidator.revalidate()}
        />
      ) : null}

      {openKid && kidChild ? (
        <ChildPopup
          key={`kid-${kidChild.id}`}
          child={kidChild}
          review={pending.find((i) => i.childId === kidChild.id && !walk.isDone(i.id))}
          approved={approved.filter((i) => i.childId === kidChild.id)}
          inbox={inbox.filter((m) => m.childId === kidChild.id)}
          initialTab={openKid.tab}
          openUnread={openKid.unread}
          onClose={() => setOpenKid(null)}
          onDecided={(insight, d) => decided(insight, d, false)}
          onStale={stale}
          onRead={() => revalidator.revalidate()}
        />
      ) : null}

      {registering ? <RegisterChildPopup onClose={() => setRegistering(false)} onRegistered={() => revalidator.revalidate()} /> : null}

      <Toast message={toast} onGone={clearToast} />
    </div>
  );
}

function countBy<T>(list: T[], key: (t: T) => string): Record<string, number> {
  const out: Record<string, number> = {};
  for (const t of list) out[key(t)] = (out[key(t)] ?? 0) + 1;
  return out;
}

/** 분석 승인 팝업에 띄울 아이. 명부에서 찾고, 없으면(그새 빠짐) 분석에 적힌 이름으로 */
function childOf(insight: Insight, children: ChildListItem[]): ChildListItem {
  return (
    children.find((c) => c.id === insight.childId) ?? {
      id: insight.childId,
      name: insight.childName,
      birthDate: "",
      externalId: null,
      status: "active",
      consent: "granted",
    }
  );
}

function KidCard({
  child,
  unread,
  onOpen,
}: {
  child: ChildListItem;
  unread: number;
  onOpen: (tab: ChildTab, unread: boolean) => void;
}) {
  const chip = guardianChip(child.consent);
  // 카드 아무 곳이나 눌러도 [열기]/[새 분석 읽기] 와 같게 연다. 키보드 사용자는 안쪽 버튼으로 연다.
  // 안쪽 버튼은 stopPropagation 으로 카드 클릭과 겹쳐 두 번 열리지 않게 한다.
  const stop = (open: () => void) => (e: MouseEvent) => {
    e.stopPropagation();
    open();
  };
  return (
    <article
      onClick={() => onOpen("analysis", unread > 0)}
      className="flex cursor-pointer flex-wrap items-center gap-x-4 gap-y-2 rounded-[18px] bg-surface px-[18px] py-3.5 shadow-[0_2px_14px_rgba(120,90,40,0.08)] transition-shadow hover:shadow-[0_4px_20px_rgba(120,90,40,0.16)]"
    >
      <span aria-hidden className="grid size-10 flex-none place-items-center rounded-full bg-surface2 text-[16px] font-bold text-ink2">
        {child.name.slice(0, 1)}
      </span>
      <div className="grid min-w-0 flex-1">
        <b className="truncate text-[16px]">{child.name}</b>
        <span className="text-[13px] tabular-nums text-muted">
          {child.birthDate}
          {child.externalId ? ` · #${child.externalId}` : ""}
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-1.5">
        {unread ? <Chip tone="human">새 분석 {unread}</Chip> : null}
        {chip ? (
          <button type="button" onClick={stop(() => onOpen("guardian", false))} className="rounded-full">
            <Chip tone="idle">{chip}</Chip>
          </button>
        ) : null}
      </div>
      {unread ? (
        <button
          type="button"
          onClick={stop(() => onOpen("analysis", true))}
          className="tap rounded-full bg-ink px-4 text-[14px] font-bold text-paper hover:brightness-110"
        >
          새 분석 읽기
        </button>
      ) : (
        <button
          type="button"
          onClick={stop(() => onOpen("analysis", false))}
          className="tap rounded-full border border-line2 px-4 text-[14px] font-bold text-ink2 hover:bg-surface2"
        >
          열기
        </button>
      )}
    </article>
  );
}

/** "우리 기관 코드 XXXX-XXXX [복사]" — 보호자가 이 코드로 연결을 요청한다 (O-01) */
function OrgCode({ code }: { code: string }) {
  const [copied, setCopied] = useState(false);
  const shown = formatInviteCode(code);
  return (
    <span data-tour="orgcode" className="flex items-center gap-2 rounded-full bg-surface py-1 pr-1 pl-4 text-[14px] shadow-[0_2px_14px_rgba(120,90,40,0.08)]">
      <span className="text-muted">우리 기관 코드</span>
      <b className="tabular-nums tracking-wide">{shown}</b>
      <button
        type="button"
        onClick={async () => {
          try {
            await navigator.clipboard.writeText(shown);
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
          } catch {
            // 복사가 막힌 브라우저 — 코드가 화면에 보이니 그대로 둔다
          }
        }}
        className="tap rounded-full border border-line2 px-3 text-[13px] font-bold text-ink2 hover:bg-surface2"
      >
        {copied ? "복사했어요" : "복사"}
      </button>
    </span>
  );
}

