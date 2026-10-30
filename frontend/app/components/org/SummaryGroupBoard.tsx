import { useEffect, useRef, useState } from "react";
import { Link, useRevalidator } from "react-router";
import { LoaderCircle, Search } from "lucide-react";
import { ApiError, getSummaryGroups, runSummary } from "@/lib/api";
import type { SummaryGroup, SummaryGroupBoard, SummaryGroupStatus } from "@/lib/types";

/**
 * 오늘의 업무 — 아이별 진행 현황 (api-spec O-34).
 *
 * 카드 한 장 = 아이 한 명의 하루치 기록이다. 매칭이 끝나 아이가 정해진 기록만 여기 오고, 그 앞(파일 · 매칭)은
 * 기록 등록 화면의 파일별 진행이 보여준다. [요약](O-32)은 아이 × 날짜 단위라 이 목록에만 있다.
 *
 * 기본은 "급한 순" — 지금 할 일 / 자동으로 진행 중 / 관리자 확인 중 으로 나눠 보인다.
 * 단계로 거르거나 날짜순으로 바꾸면 한 목록으로 이어 보인다.
 */

/** 자동으로 바뀌는 기록이 있을 때만 이 간격으로 다시 묻는다 */
const POLL_MS = 3000;

/** BE 라벨 순서 = 급한 순 */
const ORDER: SummaryGroupStatus[] = ["needs_fix", "ready", "summarizing", "validating", "admin_check"];

const STATUS: Record<SummaryGroupStatus, { label: string; pill: string; bar: string }> = {
  needs_fix: { label: "수정 필요", pill: "bg-blocksoft text-block", bar: "bg-block" },
  ready: { label: "요약 대기", pill: "bg-humansoft text-human", bar: "bg-human" },
  summarizing: { label: "요약 중", pill: "bg-accentsoft text-accentink", bar: "bg-accent/50" },
  validating: { label: "검증 중", pill: "bg-surface2 text-ink2", bar: "bg-line2" },
  admin_check: { label: "관리자 확인 중", pill: "bg-surface2 text-muted", bar: "bg-line" },
};

const SECTIONS: { title: string; desc: string; tone: string; has: SummaryGroupStatus[] }[] = [
  {
    title: "지금 할 일",
    desc: "선생님이 눌러야 다음으로 넘어가요",
    tone: "text-human",
    has: ["needs_fix", "ready"],
  },
  {
    title: "자동으로 진행 중",
    desc: "기다리면 돼요",
    tone: "text-accentink",
    has: ["summarizing", "validating"],
  },
  { title: "관리자 확인 중", desc: "선생님이 할 일은 없어요", tone: "text-muted", has: ["admin_check"] },
];

type Filter = "all" | SummaryGroupStatus;
type Sort = "urgent" | "old" | "new";

const WEEKDAY = "일월화수목금토";
function dateLabel(ymd: string): string {
  const [y, m, d] = ymd.split("-").map(Number);
  return `${m}월 ${d}일 (${WEEKDAY[new Date(y, m - 1, d).getDay()]})`;
}

const keyOf = (g: SummaryGroup) => `${g.childId}:${g.entryDate}`;

/** 라벨이 아니라 건수로 본다 — needs_fix 카드 안에서도 요약이 돌 수 있다 (O-34 화면 갱신) */
function isMoving(board: SummaryGroupBoard): boolean {
  return (
    board.matching.inProgress > 0 ||
    board.groups.some(
      (g) => g.counts.validating > 0 || g.counts.summarizing > 0 || g.summaryRequested,
    )
  );
}

/** 요약이 돌고 있는 묶음 수. 줄면 요약이 끝나 1차 검토로 간 것이다 */
function inFlight(board: SummaryGroupBoard): number {
  return board.groups.filter((g) => g.summaryRequested || g.counts.summarizing > 0).length;
}

function messageOf(err: unknown): string {
  if (!(err instanceof ApiError)) return "네트워크 연결을 확인하고 다시 시도해 주세요.";
  if (err.code === "SUMMARY_IN_PROGRESS") {
    return "이미 요약하는 중이에요. 요약이 끝나면 남은 기록을 다시 요약할 수 있어요.";
  }
  if (err.code === "SUMMARY_INVALID_REQUEST") return "요약할 기록이 없어요. 목록을 새로 불러왔어요.";
  if (err.status === 404) return "우리 기관 명부에 없는 아이예요. 목록을 새로 불러왔어요.";
  if (err.status === 401) return "로그인이 풀렸어요. 다시 로그인한 뒤 시도해 주세요.";
  if (err.status === 403) return "보안 확인에 실패했어요. 새로고침한 뒤 다시 시도해 주세요.";
  return "요약을 요청하지 못했어요. 잠시 뒤 다시 시도해 주세요.";
}

export function SummaryGroupBoardView({ initial }: { initial: SummaryGroupBoard }) {
  const revalidator = useRevalidator();
  const [board, setBoard] = useState(initial);
  const [seen, setSeen] = useState(initial);
  const [filter, setFilter] = useState<Filter>("all");
  const [sort, setSort] = useState<Sort>("urgent");
  const [query, setQuery] = useState("");
  const [busy, setBusy] = useState<Set<string>>(new Set());
  const [errors, setErrors] = useState<Record<string, string>>({});

  // 화면 전체를 다시 불러오면(배지 갱신 등) 그 값으로 맞춘다 — 렌더 중 상태 조정
  if (seen !== initial) {
    setSeen(initial);
    setBoard(initial);
  }

  const boardRef = useRef(board);
  boardRef.current = board;
  const moving = isMoving(board);

  useEffect(() => {
    if (!moving) return;
    const t = setInterval(async () => {
      try {
        const next = await getSummaryGroups();
        const finished = inFlight(next) < inFlight(boardRef.current);
        setBoard(next);
        // 요약이 끝나 1차 검토로 갔다 — 위쪽 칸과 사이드바 배지도 새로 받는다
        if (finished) revalidator.revalidate();
      } catch {
        // 다음 차례에 다시 묻는다
      }
    }, POLL_MS);
    return () => clearInterval(t);
  }, [moving, revalidator]);

  async function refresh() {
    try {
      setBoard(await getSummaryGroups());
    } catch {
      // 폴링이나 다음 방문에서 다시 받는다
    }
  }

  async function run(groups: SummaryGroup[]) {
    const keys = groups.map(keyOf);
    setBusy((b) => new Set([...b, ...keys]));
    setErrors((e) => {
      const next = { ...e };
      for (const k of keys) delete next[k];
      return next;
    });
    // 묶음마다 따로 요청한다 — 한 번에 요약하는 API 는 없다
    for (const g of groups) {
      try {
        await runSummary(g.childId, g.entryDate);
      } catch (err) {
        setErrors((e) => ({ ...e, [keyOf(g)]: messageOf(err) }));
      }
    }
    await refresh();
    setBusy((b) => new Set([...b].filter((k) => !keys.includes(k))));
  }

  const q = query.trim();
  const counted = (st: SummaryGroupStatus) => board.groups.filter((g) => g.status === st).length;
  const shown = board.groups
    .filter((g) => (filter === "all" || g.status === filter) && (!q || g.childName.includes(q)))
    .sort((a, b) => {
      const byDate = a.entryDate.localeCompare(b.entryDate);
      if (sort === "urgent") return ORDER.indexOf(a.status) - ORDER.indexOf(b.status) || byDate;
      return sort === "old" ? byDate : -byDate;
    });

  const sections =
    filter === "all" && sort === "urgent"
      ? SECTIONS.map((s) => ({ ...s, groups: shown.filter((g) => s.has.includes(g.status)) })).filter(
          (s) => s.groups.length > 0,
        )
      : shown.length > 0
        ? [
            {
              title:
                filter !== "all"
                  ? STATUS[filter].label
                  : sort === "old"
                    ? "오래된 날짜순"
                    : "최신 날짜순",
              desc: "아이 × 날짜",
              tone: "text-ink",
              groups: shown,
            },
          ]
        : [];

  const ready = board.groups.filter((g) => g.status === "ready" && g.canSummarize);
  const todo = counted("needs_fix") + counted("ready");
  const { inProgress, needsReview } = board.matching;

  const filters: { key: Filter; label: string; count: number; dot: string }[] = [
    { key: "all" as Filter, label: "전체", count: board.groups.length, dot: "bg-ink" },
    ...ORDER.map((st) => ({
      key: st,
      label: STATUS[st].label,
      count: counted(st),
      dot: STATUS[st].bar,
    })),
  ].filter((f) => f.key === "all" || f.count > 0);

  return (
    <section aria-labelledby="groups-title" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div>
          <h2 id="groups-title" className="text-[19px] font-bold">
            아이별 진행 현황
          </h2>
          <p className="mt-1 text-[14px] text-muted">
            {todo > 0
              ? `지금 선생님이 할 일이 ${todo}명 있어요. 급한 것부터 보여드려요.`
              : "지금 선생님이 할 일은 없어요."}{" "}
            파일별 진행은{" "}
            <Link to="/upload" className="text-accentink underline">
              기록 등록
            </Link>
            에서 볼 수 있어요.
          </p>
        </div>
        {moving ? (
          <p className="flex items-center gap-1.5 text-[13px] text-accentink">
            <span aria-hidden className="size-2 rounded-full bg-accent" />
            자동으로 새로 고치는 중
          </p>
        ) : null}
      </div>

      {inProgress > 0 || needsReview > 0 ? (
        <p className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded bg-paper px-3.5 py-2.5 text-[14px] text-ink2">
          <span className="font-bold">아직 아이가 안 정해진 기록</span>
          {inProgress > 0 ? (
            <span>
              아이 찾는 중 <b className="font-bold">{inProgress}건</b>
            </span>
          ) : null}
          {needsReview > 0 ? (
            <Link to="/queue/matching" className="font-semibold text-accentink underline">
              확인 필요 {needsReview}건 →
            </Link>
          ) : null}
        </p>
      ) : null}

      {board.groups.length === 0 ? (
        <p className="rounded border border-dashed border-line2 px-4 py-10 text-center text-[15px] text-muted">
          요약을 기다리는 기록이 없어요. 요약이 끝난 기록은{" "}
          <Link to="/gate1" className="text-accentink underline">
            1차 검토
          </Link>
          에 있어요.
        </p>
      ) : (
        <>
          <div role="group" aria-label="단계로 거르기" className="flex flex-wrap gap-1.5">
            {filters.map((f) => {
              const on = filter === f.key;
              return (
                <button
                  key={f.key}
                  type="button"
                  aria-pressed={on}
                  onClick={() => setFilter(f.key)}
                  className={`flex min-h-10 items-center gap-1.5 rounded-full border px-3.5 text-[14px] font-semibold ${
                    on
                      ? "border-ink bg-ink text-white"
                      : "border-line2 bg-surface text-ink2 hover:bg-surface2"
                  }`}
                >
                  <span aria-hidden className={`size-2 rounded-full ${on ? "bg-white" : f.dot}`} />
                  {f.label} <span className="font-bold tabular-nums">{f.count}</span>
                </button>
              );
            })}
          </div>

          <div className="flex flex-wrap items-end gap-3">
            <label className="flex min-w-[220px] flex-1 flex-col gap-1 text-[13px] text-ink2">
              아이 이름으로 찾기
              <span className="relative">
                <Search
                  aria-hidden
                  size={16}
                  className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-muted"
                />
                <input
                  type="search"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder="예: 김하늘"
                  className="min-h-10 w-full rounded border border-line2 bg-surface pr-3 pl-9 text-[15px] text-ink outline-none focus:border-accent"
                />
              </span>
            </label>
            <label className="flex flex-col gap-1 text-[13px] text-ink2">
              정렬
              <select
                value={sort}
                onChange={(e) => setSort(e.target.value as Sort)}
                className="min-h-10 rounded border border-line2 bg-surface px-2.5 text-[15px] text-ink"
              >
                <option value="urgent">급한 순 (할 일 먼저)</option>
                <option value="old">오래된 날짜순</option>
                <option value="new">최신 날짜순</option>
              </select>
            </label>
            {ready.length > 1 ? (
              <button
                type="button"
                onClick={() => run(ready)}
                disabled={ready.some((g) => busy.has(keyOf(g)))}
                className="min-h-10 rounded border border-human bg-surface px-4 text-[14px] font-bold text-human hover:bg-humansoft/50 disabled:opacity-60"
              >
                요약 대기 {ready.length}건 모두 요약
              </button>
            ) : null}
          </div>

          {sections.length === 0 ? (
            <p className="rounded border border-dashed border-line2 px-4 py-9 text-center text-[15px] text-muted">
              조건에 맞는 아이가 없어요. 거르기나 검색어를 바꿔 보세요.
            </p>
          ) : (
            sections.map((s) => (
              <div key={s.title} className="flex flex-col gap-1.5">
                <div className="flex items-baseline gap-2 px-0.5 pt-1.5 pb-1">
                  <h3 className={`text-[15px] font-bold ${s.tone}`}>{s.title}</h3>
                  <span className="text-[14px] text-muted">
                    {s.groups.length}명 · {s.desc}
                  </span>
                </div>
                <ul className="overflow-hidden rounded border border-line">
                  {s.groups.map((g) => (
                    <GroupRow
                      key={keyOf(g)}
                      group={g}
                      busy={busy.has(keyOf(g))}
                      error={errors[keyOf(g)]}
                      onRun={() => run([g])}
                    />
                  ))}
                </ul>
              </div>
            ))
          )}
        </>
      )}
    </section>
  );
}

function GroupRow({
  group: g,
  busy,
  error,
  onRun,
}: {
  group: SummaryGroup;
  busy: boolean;
  error?: string;
  onRun: () => void;
}) {
  const st = STATUS[g.status];
  const c = g.counts;
  // [요약] 세 갈래: 강조 · 수정 요청이 섞이면 약하게 · 누를 수 없으면 꺼짐
  const mode = !g.canSummarize ? "off" : c.needsFix > 0 ? "weak" : "strong";

  const chips: { text: string; cls: string; href?: string }[] = [];
  if (c.needsFix) {
    chips.push({ text: `수정 요청 ${c.needsFix}`, cls: "bg-blocksoft text-block", href: "/queue/reinput" });
  }
  if (c.ready) chips.push({ text: `요약 대기 ${c.ready}`, cls: "bg-humansoft text-human" });
  if (c.validating) chips.push({ text: `검증 중 ${c.validating}`, cls: "bg-surface2 text-ink2" });
  if (c.summarizing) chips.push({ text: `요약 중 ${c.summarizing}`, cls: "bg-accentsoft text-accentink" });
  if (c.adminCheck) chips.push({ text: `관리자 확인 중 ${c.adminCheck}`, cls: "bg-paper text-muted" });

  let hint = "";
  if (g.summaryRequested) {
    hint =
      g.waitingCount > 0
        ? `요약 요청됨 · 같은 날 기록 ${g.waitingCount}건 처리 뒤 시작`
        : "요약 중 · 끝나면 1차 검토로 옮겨져요";
  } else if (mode === "weak") hint = "지금 요약해도 돼요. 고친 기록은 다음 요약에 들어가요";
  else if (g.status === "needs_fix") hint = "수정 요청을 처리하면 요약할 수 있어요";
  else if (g.status === "validating") hint = "검증이 끝나면 요약할 수 있어요";
  else if (g.status === "admin_check") hint = "선생님이 할 일은 없어요";
  if (g.previousSummaryInReview) {
    hint = [hint, "1차 검토에 이전 요약이 있어 다시 요약하면 새 판으로 바뀌어요"]
      .filter(Boolean)
      .join(" · ");
  }

  const date = dateLabel(g.entryDate);
  return (
    <li
      className={`flex flex-wrap items-center gap-x-3.5 gap-y-2 border-t border-surface2 px-3.5 py-3 first:border-t-0 ${
        g.status === "ready" ? "bg-humansoft/25" : "bg-surface hover:bg-paper/50"
      }`}
    >
      <span aria-hidden className={`w-1 self-stretch rounded-full ${st.bar}`} />
      <div className="flex min-w-0 flex-[1_1_140px] flex-col">
        <span className="text-[16px] font-bold">{g.childName}</span>
        {g.birthdate ? <span className="text-[12px] text-muted">{g.birthdate}</span> : null}
      </div>
      <span className="w-[108px] shrink-0 text-[14px] font-semibold text-ink2">{date}</span>
      <span className={`shrink-0 rounded-full px-2.5 py-0.5 text-[13px] font-bold ${st.pill}`}>
        {st.label}
      </span>
      <div className="flex min-w-0 flex-[3_1_260px] flex-col gap-1">
        <ul className="flex flex-wrap gap-1">
          {chips.map((chip) => (
            <li key={chip.text} className={`rounded px-2 py-0.5 text-[13px] ${chip.cls}`}>
              {chip.href ? (
                <Link to={chip.href} className="font-semibold underline">
                  {chip.text} →
                </Link>
              ) : (
                chip.text
              )}
            </li>
          ))}
        </ul>
        {hint ? (
          <p className="flex items-center gap-1.5 text-[13px] text-muted">
            {g.summaryRequested ? (
              <LoaderCircle aria-hidden size={13} className="shrink-0 animate-spin text-accent" />
            ) : null}
            {hint}
          </p>
        ) : null}
        {error ? (
          <p role="alert" className="text-[13px] text-block">
            {error}
          </p>
        ) : null}
      </div>
      <button
        type="button"
        aria-label={`${g.childName} ${date} 요약`}
        onClick={onRun}
        disabled={mode === "off" || busy}
        className={`tap shrink-0 rounded px-4.5 text-[15px] font-bold ${
          mode === "strong"
            ? "bg-human text-white hover:brightness-95"
            : mode === "weak"
              ? "border border-human/60 bg-surface text-human hover:bg-humansoft/40"
              : "bg-surface2 text-muted"
        } disabled:cursor-not-allowed`}
      >
        {busy ? "요청 중…" : "요약"}
      </button>
    </li>
  );
}
