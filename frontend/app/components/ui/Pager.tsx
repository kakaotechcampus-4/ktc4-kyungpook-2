import { useState } from "react";

/** 한 쪽에 보이는 기본 건수. 기록 확인 두 칸만 5건 */
export const PAGE_SIZE = 10;

/**
 * 목록을 쪽으로 자른다. 쪽 번호는 화면 상태로만 두고 URL 에는 남기지 않는다.
 * 목록이 줄어 지금 쪽이 사라지면 마지막 쪽으로 당긴다(처리한 건이 빠질 때).
 */
export function usePage<T>(list: T[], size = PAGE_SIZE) {
  const [page, setPage] = useState(1);
  const pages = Math.max(1, Math.ceil(list.length / size));
  const p = Math.min(Math.max(1, page), pages);
  return {
    items: list.slice((p - 1) * size, p * size),
    /** 이 쪽 첫 건의 전체 목록 순번(0부터) */
    from: (p - 1) * size,
    page: p,
    pages,
    total: list.length,
    size,
    setPage,
  };
}

/** 7쪽 이하면 모두, 넘으면 처음·끝·지금 쪽 앞뒤만 두고 나머지는 … */
function pageNumbers(p: number, pages: number): (number | "…")[] {
  const nums: (number | "…")[] = [];
  for (let i = 1; i <= pages; i++) {
    if (pages <= 7 || i === 1 || i === pages || Math.abs(i - p) <= 1) nums.push(i);
    else if (nums[nums.length - 1] !== "…") nums.push("…");
  }
  return nums;
}

/** ‹ 1 2 3 › 와 "1–10 / 22건". 한 쪽뿐이면 그리지 않는다 */
export function Pager({
  page,
  pages,
  total,
  size,
  unit = "건",
  onChange,
}: {
  page: number;
  pages: number;
  total: number;
  size: number;
  unit?: string;
  onChange: (page: number) => void;
}) {
  if (pages <= 1) return null;
  const base =
    "h-9 min-w-9 rounded-[10px] px-2 text-[14px] font-bold tabular-nums focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent";
  const arrow = `${base} text-ink2 hover:bg-surface2 disabled:cursor-default disabled:text-line2 disabled:hover:bg-transparent`;
  return (
    <nav aria-label="쪽 이동" className="mt-3 flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
      <span className="text-[13px] tabular-nums text-muted">
        {(page - 1) * size + 1}–{Math.min(page * size, total)} / {total}
        {unit}
      </span>
      <div className="flex items-center gap-1">
        <button type="button" className={arrow} disabled={page === 1} aria-label="이전 쪽" onClick={() => onChange(page - 1)}>
          ‹
        </button>
        {pageNumbers(page, pages).map((n, i) =>
          n === "…" ? (
            <span key={`gap-${i}`} className="min-w-5 text-center text-muted">
              …
            </span>
          ) : (
            <button
              key={n}
              type="button"
              aria-label={`${n}쪽`}
              aria-current={n === page ? "page" : undefined}
              onClick={() => onChange(n)}
              className={`${base} ${n === page ? "bg-ink text-paper" : "text-ink2 hover:bg-surface2"}`}
            >
              {n}
            </button>
          ),
        )}
        <button type="button" className={arrow} disabled={page === pages} aria-label="다음 쪽" onClick={() => onChange(page + 1)}>
          ›
        </button>
      </div>
    </nav>
  );
}
