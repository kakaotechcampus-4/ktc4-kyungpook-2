import { useState } from "react";
import type { Child } from "@/lib/types";

/**
 * 명부에서 아이를 직접 찾아 고른다 — 매칭 확인(unmatched · "다른 아이")과 Gate 1 아동 변경이 함께 쓴다.
 *
 * 동명이인이 있을 수 있어 이름만 보여주지 않고 생년월일을 항상 붙인다.
 * 보호자 동의 전인 아이는 파이프라인이 돌지 않으므로 목록에서 뺀다.
 */
export function RosterPicker({
  roster,
  value,
  onChange,
  excludeId,
  name,
}: {
  roster: Child[];
  value: string | null;
  onChange: (childId: string) => void;
  /** 지금 지정된 아이 — "다른 아이" 를 고르는 중이라 목록에서 뺀다 */
  excludeId?: string;
  /** 라디오 그룹 이름. 한 화면에 여러 개 있을 수 있어 받는다 */
  name: string;
}) {
  const [query, setQuery] = useState("");
  const q = query.trim();
  const candidates = roster.filter((c) => c.status === "active" && c.id !== excludeId);
  const results = q ? candidates.filter((c) => c.name.includes(q)) : candidates;

  return (
    <div className="flex flex-col gap-2">
      <label className="flex flex-col gap-1.5">
        <span className="text-[15px] font-semibold">명부에서 찾기</span>
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="아이 이름 입력"
          className="tap rounded border border-line2 px-3 text-[16px] outline-none focus:border-accent"
        />
      </label>

      {results.length === 0 ? (
        <p className="rounded bg-surface2 px-3 py-2 text-[14px] text-muted">
          {q ? `"${q}" 와(과) 일치하는 아이가 명부에 없습니다.` : "고를 수 있는 아이가 없습니다."}
        </p>
      ) : (
        <fieldset className="flex max-h-72 flex-col gap-2 overflow-y-auto">
          <legend className="sr-only">명부 검색 결과</legend>
          {results.map((c) => (
            <label
              key={c.id}
              className="tap flex cursor-pointer items-center gap-3 rounded border border-line2 px-3 hover:border-accent has-checked:border-accent has-checked:bg-accentsoft"
            >
              <input
                type="radio"
                name={name}
                className="size-4"
                checked={value === c.id}
                onChange={() => onChange(c.id)}
              />
              <span
                aria-hidden
                className="flex size-8 items-center justify-center rounded-full bg-surface2 text-[14px] font-semibold text-ink2"
              >
                {c.name.slice(0, 1)}
              </span>
              <span>
                <span className="block text-[16px] font-semibold">{c.name}</span>
                <span className="block text-[13px] text-muted tabular-nums">
                  {c.school ? `${c.school} · ` : ""}
                  {c.birthDate}
                </span>
              </span>
            </label>
          ))}
        </fieldset>
      )}
    </div>
  );
}
