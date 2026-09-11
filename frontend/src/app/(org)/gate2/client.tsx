"use client";

import { useState } from "react";
import {
  Card,
  ConsentStatus,
  EvidenceChip,
  InstitutionChip,
  IrreversibleWarning,
  Note,
} from "@/components/ui";
import type { Insight } from "@/lib/types";

type View = "review" | "sent" | "held";

export function Gate2Client({ insight }: { insight: Insight }) {
  /** 기본값은 전체 미선택이다. 미리 켜두지 않는다. */
  const [selected, setSelected] = useState<string[]>([]);
  const [view, setView] = useState<View>("review");

  const selectable = insight.targets.filter((t) => t.consent === "granted");
  const blocked = insight.targets.filter((t) => t.consent !== "granted");

  const toggle = (id: string) =>
    setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  if (view === "sent") {
    const names = insight.targets
      .filter((t) => selected.includes(t.institution.id))
      .map((t) => t.institution.name);
    return (
      <Card className="border-pass/40 bg-passsoft/50">
        <p className="mb-2 text-[16px] font-bold text-pass">발송 완료</p>
        <p className="mb-3 text-[15px] leading-7 text-ink2">
          <b className="font-semibold">{names.join(", ")}</b> 에 전달했습니다. 각 기관에는
          동의 범위에 맞게 변환된 최소 정보만 전달됩니다.
        </p>
        <Note>
          발송은 되돌릴 수 없습니다. 전달 이력은 활동 이력에서 확인할 수 있습니다.
        </Note>
      </Card>
    );
  }

  if (view === "held") {
    return (
      <Card className="border-line2">
        <p className="mb-2 text-[16px] font-bold text-ink2">보류됨</p>
        <p className="text-[15px] leading-7 text-muted">
          이 Insight 는 발송하지 않았습니다. 다시 검토하려면 Insight 목록에서 열어주세요.
        </p>
      </Card>
    );
  }

  return (
    <Card>
      <div className="mb-4 flex flex-wrap items-center gap-2.5 border-b border-line pb-3">
        <span
          aria-hidden
          className="flex size-8 items-center justify-center rounded-full bg-surface2 text-[14px] font-semibold text-ink2"
        >
          {insight.childName.slice(0, 1)}
        </span>
        <span className="text-[16px] font-bold">{insight.childName}</span>
        <span className="text-[14px] text-muted">Insight · {insight.period}</span>
      </div>

      <p className="mb-4 rounded bg-surface2 p-3 text-[16px] leading-7">{insight.content}</p>

      {/* 승인 주체가 자기 기관 근거인지 확인할 수 있어야 한다 */}
      <p className="mb-2 flex flex-wrap items-center gap-1.5 text-[14px] text-ink2">
        <span className="font-semibold">근거를 제공한 기관</span>
        <span className="text-muted">·</span>
        <InstitutionChip institution={insight.primarySource} withName />
      </p>

      <p className="mb-2 text-[13px] font-semibold tracking-wider text-muted uppercase">
        근거 기록 {insight.evidence.length}건
      </p>
      <ul className="mb-6 flex flex-col items-start gap-2">
        {insight.evidence.map((e) => (
          <li key={e.childContextId}>
            <EvidenceChip
              date={e.date}
              label={e.label}
              institution={e.institution}
              href={`/children/${insight.childId}`}
            />
          </li>
        ))}
      </ul>

      <fieldset className="mb-4">
        <legend className="mb-1 text-[17px] font-bold">받는 기관 선택</legend>
        <p className="mb-3 text-[14px] text-muted">
          체크한 기관에만 변환된 최소 정보가 전달됩니다. 아무것도 선택하지 않은 상태로
          시작합니다.
        </p>

        <div className="flex flex-col gap-2">
          {selectable.map((t) => (
            <label
              key={t.institution.id}
              className="tap flex cursor-pointer items-center gap-3 rounded border border-line2 px-3 hover:border-accent has-checked:border-accent has-checked:bg-accentsoft"
            >
              <input
                type="checkbox"
                className="size-4"
                checked={selected.includes(t.institution.id)}
                onChange={() => toggle(t.institution.id)}
              />
              <InstitutionChip institution={t.institution} withName />
              <span className="ml-auto">
                <ConsentStatus state={t.consent} />
              </span>
            </label>
          ))}

          {blocked.map((t) => (
            <div
              key={t.institution.id}
              aria-disabled
              className="tap flex items-center gap-3 rounded border border-line bg-surface2/70 px-3 opacity-80"
            >
              <span aria-hidden className="text-[15px] text-muted">
                🔒
              </span>
              <InstitutionChip institution={t.institution} withName />
              <span className="ml-auto flex flex-wrap items-center gap-2">
                <ConsentStatus state={t.consent} />
                <span className="text-[13px] text-muted">
                  {t.consent === "revoked"
                    ? "보호자가 공유를 회수한 기관입니다"
                    : "보호자가 동의하지 않은 기관입니다"}
                </span>
              </span>
            </div>
          ))}
        </div>
      </fieldset>

      <div className="mb-4">
        <IrreversibleWarning />
      </div>

      <div className="flex flex-wrap gap-2">
        <button
          onClick={() => setView("sent")}
          disabled={selected.length === 0}
          className="tap rounded bg-human px-5 text-[16px] font-semibold text-white hover:brightness-95 disabled:cursor-not-allowed disabled:bg-line2 disabled:text-muted"
        >
          발송 승인 ({selected.length}곳 선택됨)
        </button>
        <button
          onClick={() => setView("held")}
          className="tap rounded border border-line2 px-4 text-[16px] font-semibold text-ink2 hover:bg-surface2"
        >
          보류
        </button>
        <button className="tap rounded border border-line2 px-4 text-[16px] font-semibold text-ink2 hover:bg-surface2">
          태깅 수정
        </button>
      </div>

      {selected.length === 0 ? (
        <p className="mt-3 text-[14px] text-muted">
          받는 기관을 한 곳 이상 선택하면 발송할 수 있습니다.
        </p>
      ) : null}
    </Card>
  );
}
