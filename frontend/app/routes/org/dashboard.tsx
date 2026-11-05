import type { LucideIcon } from "lucide-react";
import {
  ArrowRight,
  ClipboardCheck,
  ListChecks,
  RotateCcw,
  Send,
  Upload,
} from "lucide-react";
import { useEffect } from "react";
import { Link, useLoaderData, useRevalidator } from "react-router";
import { SummaryGroupBoardView } from "@/components/org/SummaryGroupBoard";
import { BigNum, Card, InstitutionChip, PageHeader, PanelHead } from "@/components/ui";
import {
  getBlockedQueue,
  getGate1Queue,
  getInsightProgress,
  getInsights,
  getMatchingQueue,
  getSummaryGroups,
  isReady,
  loadIfReady,
} from "@/lib/api";
import { isAuthMock } from "@/lib/auth";
import type { InsightProgress } from "@/lib/types";
import { MY_INSTITUTION } from "@/lib/mock/data";

/** 백엔드에 아직 없는 기능은 null 로 와서 "준비 중"으로 그린다 (loadIfReady 참고) */
export async function clientLoader() {
  const [matching, blocked, gate1, held, insights, board] = await Promise.all([
    loadIfReady("matchingQueue", getMatchingQueue),
    loadIfReady("blockedQueue", getBlockedQueue),
    loadIfReady("gate1", () => getGate1Queue("pending")),
    loadIfReady("gate1", () => getGate1Queue("hold")),
    loadIfReady("insights", getInsights),
    loadIfReady("summaryGroups", getSummaryGroups),
  ]);
  return { matching, blocked, gate1, held, insights, board };
}

const INSIGHT_POLL_MS = 4000;
/** 분석이 이보다 오래 걸리면 더 기다리지 않는다 — 워커가 꺼졌거나 멈춘 서버에서 계속 묻지 않게 */
const INSIGHT_POLL_LIMIT_MS = 5 * 60 * 1000;

const analyzing = (list: InsightProgress[]) =>
  list.some((p) => p.status === "queued" || p.status === "running");

/**
 * 요약 검토에서 승인하면 인사이트 분석은 따로 돈다. 대시보드를 열었을 때 분석 중이면 진행 현황(O-43)만 4초마다 다시
 * 묻고, 분석이 끝나면(done · nothing_new 등) 화면 전체를 한 번 다시 받아 "인사이트 승인" 건수와 메뉴 배지를 새로고침
 * 없이 띄운다 (#359). 진행 현황은 서버가 묻는 주마다 재료를 다시 만들어 보는 무거운 조회라, 화면 전체를 다시 받을 때마다
 * 함께 부르지 않고 여기서만 부른다. 실패 뒤 다시 시도를 기다리는 동안(retrying)은 언제 끝날지 몰라 기다리지 않는다.
 */
function useInsightPolling() {
  const revalidator = useRevalidator();
  useEffect(() => {
    if (!isReady("insights")) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const startedAt = Date.now();

    async function check(wasAnalyzing: boolean) {
      let now = false;
      try {
        now = analyzing(await getInsightProgress());
      } catch {
        return; // 진행 현황을 못 받으면 기다리지 않는다 — 화면은 그대로 쓸 수 있다
      }
      if (stopped) return;
      if (wasAnalyzing && !now) revalidator.revalidate();
      if (now && Date.now() - startedAt < INSIGHT_POLL_LIMIT_MS) {
        timer = setTimeout(() => check(true), INSIGHT_POLL_MS);
      }
    }
    check(false);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
    // 화면에 들어올 때 한 번 시작한다. revalidator 는 렌더마다 새 객체라 넣으면 다시 시작한다
  }, []);
}

const WEEKDAY = "일월화수목금토";
function todayLabel(): string {
  // 기관 PC 의 시계를 그대로 쓴다 — 서버 시각을 따로 받지 않는다
  const d = new Date();
  return `${d.getMonth() + 1}월 ${d.getDate()}일 (${WEEKDAY[d.getDay()]})`;
}

/** 내 차례 한 줄. count 가 null 이면 백엔드가 아직 없는 기능 — 0 으로 세지 않는다 */
interface Turn {
  href: string;
  label: string;
  icon: LucideIcon;
  count: number | null;
}

/**
 * 오늘의 업무 — 캔버스 02-today 의 두 칸.
 *
 * 왼쪽 "내 차례": 사람이 눌러야 넘어가는 네 가지를 **한 목록**으로 모으고, 쌓인 것 하나만 위로 올려
 * 시작 단추를 단다. 전에는 같은 크기 네모 넷이라 어디부터 손대야 할지가 화면에 없었다.
 * 오른쪽 "아직 아이가 안 정해진 기록": 아이가 정해져야 아래 목록에 오르므로 그 앞단을 따로 세운다.
 * 아래 "아이별 진행 현황"(O-34)은 그대로다 — 파일별 진행(O-26)은 기록 등록 화면이 보여준다.
 */
export default function DashboardPage() {
  const { matching, blocked, gate1, held, insights, board } = useLoaderData<typeof clientLoader>();
  useInsightPolling();

  const turns: Turn[] = [
    {
      href: "/queue/matching",
      label: "아이 확인",
      icon: ClipboardCheck,
      count: matching?.length ?? null,
    },
    {
      href: "/gate1",
      label: "요약 확인",
      icon: ListChecks,
      count: gate1?.length ?? null,
    },
    {
      href: "/gate2",
      label: "인사이트 승인",
      icon: Send,
      count: insights?.length ?? null,
    },
    {
      href: "/queue/reinput",
      label: "수정 요청",
      icon: RotateCcw,
      count: blocked?.length ?? null,
    },
  ];

  // 제일 많이 쌓인 한 줄만 위로 올린다. 다 0 이면 아무것도 올리지 않는다
  const lead = turns.reduce<Turn | null>(
    (best, t) => ((t.count ?? 0) > (best?.count ?? 0) ? t : best),
    null,
  );
  const rest = turns.filter((t) => t !== lead);

  // 준비 중인 칸은 0건으로 치지 않는다 — 모르는 걸 "할 일 없음"이라고 말하면 안 된다
  const total = turns.reduce((n, t) => n + (t.count ?? 0), 0);
  const heldCount = held?.length ?? null;

  const pending = board?.matching ?? null;

  return (
    <>
      <PageHeader
        eyebrow={todayLabel()}
        title={total > 0 ? `오늘 선생님 차례 ${total}건` : "오늘 선생님 차례가 없습니다"}
        // 실연동에서는 숨긴다. 내 기관명을 받을 API 가 아직 없어, mock 기관명을 띄우면 다른 기관이 보인다
        right={isAuthMock() ? <InstitutionChip institution={MY_INSTITUTION} withName /> : null}
      />

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.65fr)_minmax(0,1fr)]">
        <Card>
          <PanelHead title="내 차례" />

          {lead && (lead.count ?? 0) > 0 ? (
            <Link
              to={lead.href}
              className="mb-2 flex flex-wrap items-center gap-4 rounded-[18px] bg-reviewsoft px-5 py-4 transition-colors hover:brightness-[0.98]"
            >
              <span
                aria-hidden
                className="flex size-12 shrink-0 items-center justify-center rounded-full bg-mark text-markink"
              >
                <lead.icon size={22} strokeWidth={2} />
              </span>
              <span className="min-w-0 flex-1 text-[19px] font-extrabold text-ink">
                {lead.label} {lead.count}건
              </span>
              <span className="tap flex shrink-0 items-center gap-1.5 rounded-full bg-ink px-5 text-[15px] font-bold text-paper">
                시작하기
                <ArrowRight aria-hidden size={16} strokeWidth={2.2} />
              </span>
            </Link>
          ) : null}

          <ul className="flex flex-col">
            {rest.map((t) => {
              const Icon = t.icon;
              return (
                <li key={t.href} className="border-t border-line first:border-t-0">
                  <Link
                    to={t.href}
                    className="flex items-center gap-4 rounded-[14px] px-3 py-3.5 transition-colors hover:bg-surface2"
                  >
                    <span
                      aria-hidden
                      className="flex size-10 shrink-0 items-center justify-center rounded-full bg-surface2 text-ink2"
                    >
                      <Icon size={19} strokeWidth={1.9} />
                    </span>
                    <span className="min-w-0 flex-1 text-[16px] font-bold text-ink">{t.label}</span>
                    {t.count === null ? (
                      <span className="shrink-0 text-[14px] text-muted">준비 중</span>
                    ) : (
                      <span
                        className={`shrink-0 text-[22px] font-extrabold tabular-nums ${
                          t.count === 0 ? "text-line2" : "text-ink"
                        }`}
                      >
                        {t.count}
                      </span>
                    )}
                  </Link>
                </li>
              );
            })}
          </ul>

          {heldCount !== null && heldCount > 0 ? (
            <p className="mt-3 border-t border-line pt-3.5 text-[14px] text-muted">
              <Link to="/gate1?tab=hold" className="font-semibold text-ink2 hover:underline">
                보류함 {heldCount}건
              </Link>
            </p>
          ) : null}
        </Card>

        <Card>
          <PanelHead
            title="아직 아이가 안 정해진 기록"
            right={
              <Link
                to="/upload"
                className="tap flex shrink-0 items-center gap-1.5 rounded-full border border-line2 px-4 text-[15px] font-bold text-ink2 hover:bg-surface2"
              >
                <Upload aria-hidden size={15} strokeWidth={2} />
                올리기
              </Link>
            }
          />

          {pending === null ? (
            <p className="text-[14px] text-muted">준비 중입니다.</p>
          ) : pending.inProgress === 0 && pending.needsReview === 0 ? (
            <p className="rounded-[14px] bg-accentsoft px-4 py-5 text-[15px] leading-6 text-accentink">
              올린 기록이 모두 아이에게 연결됐어요.
            </p>
          ) : (
            <div className="flex flex-col gap-1">
              <div className="flex items-end justify-between gap-3 py-2.5">
                <span className="text-[15px] text-ink2">AI 가 찾는 중</span>
                <BigNum value={pending.inProgress} label="건" tone="accent" />
              </div>
              <div className="border-t border-line" />
              <Link
                to="/queue/matching"
                className="flex items-end justify-between gap-3 rounded-[14px] py-2.5 transition-colors hover:bg-surface2"
              >
                <span className="text-[15px] font-bold text-ink">
                  내가 고를 것
                  <ArrowRight
                    aria-hidden
                    size={15}
                    strokeWidth={2.2}
                    className="ml-1 inline align-[-2px]"
                  />
                </span>
                <BigNum value={pending.needsReview} label="건" tone="review" />
              </Link>
            </div>
          )}
        </Card>
      </div>

      <Card className="mt-5">
        {board === null ? (
          <>
            <PanelHead title="아이별 진행 현황" />
            <p className="text-[14px] text-muted">준비 중입니다.</p>
          </>
        ) : (
          <SummaryGroupBoardView initial={board} />
        )}
      </Card>
    </>
  );
}
