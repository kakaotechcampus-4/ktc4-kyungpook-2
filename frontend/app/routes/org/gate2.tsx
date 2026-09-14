import { useState } from "react";
import { useLoaderData, useRevalidator } from "react-router";
import {
  Card,
  ConsentStatus,
  EmptyState,
  EvidenceChip,
  InstitutionChip,
  IrreversibleWarning,
  Note,
  PageHeader,
} from "@/components/ui";
import { decideGate2, getInsights } from "@/lib/api";
import { MY_INSTITUTION } from "@/lib/mock/data";
import type { Insight } from "@/lib/types";

export async function clientLoader() {
  const insights = await getInsights();
  // 승인 주체는 근거 요약을 최다 제공한 기관이다. 내 기관이 근거 제공자인 것만 보인다.
  const mine = insights.filter(
    (i) => i.primarySource.id === MY_INSTITUTION.id && i.gate2Status === "pending",
  );
  return { mine };
}

type View = "review" | "sent" | "held";

function Gate2Item({
  insight,
  initialView = "review",
  onResolved,
}: {
  insight: Insight;
  initialView?: View;
  onResolved: (view: View) => void;
}) {
  /** 기본값은 전체 미선택이다. 미리 켜두지 않는다. */
  const [selected, setSelected] = useState<string[]>([]);
  const [view, setView] = useState<View>(initialView);
  const revalidator = useRevalidator();

  async function send() {
    await decideGate2(insight.id, { decision: "approve", target_institution_ids: selected });
    setView("sent");
    onResolved("sent");
    revalidator.revalidate();
  }

  async function hold() {
    await decideGate2(insight.id, { decision: "hold", target_institution_ids: selected });
    setView("held");
    onResolved("held");
    revalidator.revalidate();
  }

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
          <b className="font-semibold">{names.join(", ")}</b> 에 전달했습니다. 각 기관에는 동의
          범위에 맞게 변환된 최소 정보만 전달됩니다.
        </p>
        <Note>발송은 되돌릴 수 없습니다. 전달 이력은 활동 이력에서 확인할 수 있습니다.</Note>
      </Card>
    );
  }

  if (view === "held") {
    return (
      <Card className="border-line2">
        <p className="mb-2 text-[16px] font-bold text-ink2">보류됨</p>
        <p className="text-[15px] leading-7 text-muted">
          이 기록은 공유하지 않았습니다. 다시 검토하려면 관찰·분석 결과 목록에서 열어주세요.
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
        <span className="text-[14px] text-muted">분석 결과 · {insight.period}</span>
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
          onClick={send}
          disabled={selected.length === 0}
          className="tap rounded bg-human px-5 text-[16px] font-semibold text-white hover:brightness-95 disabled:cursor-not-allowed disabled:bg-line2 disabled:text-muted"
        >
          발송 승인 ({selected.length}곳 선택됨)
        </button>
        <button
          onClick={hold}
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

export default function Gate2Page() {
  const { mine } = useLoaderData<typeof clientLoader>();
  /**
   * 승인/보류하면 revalidate 로 사이드바 배지가 갱신되면서 이 항목은 `mine`(pending
   * 필터) 에서 빠진다 — "완료" 카드가 화면에서 사라지지 않도록 이번 방문 동안만
   * 로컬로 기억해서 함께 보여준다.
   */
  const [resolved, setResolved] = useState<Record<string, { view: View; insight: Insight }>>(
    {},
  );
  const pendingItems = mine.filter((i) => !resolved[i.id]);
  const visible = [...pendingItems, ...Object.values(resolved).map((r) => r.insight)];

  return (
    <>
      <PageHeader
        title="공유 전 검토"
        description="근거 기록을 제공한 선생님이 발송 적절성을 판단합니다"
      />
      {visible.length === 0 ? (
        <EmptyState
          icon="✓"
          title="공유할 기록이 없습니다"
          description="우리 기관 기록이 근거가 된 관찰·분석 결과가 생기면 여기에 표시됩니다."
        />
      ) : (
        <div className="flex flex-col gap-6">
          {visible.map((i) => (
            <Gate2Item
              key={i.id}
              insight={i}
              initialView={resolved[i.id]?.view ?? "review"}
              onResolved={(view) => setResolved((r) => ({ ...r, [i.id]: { view, insight: i } }))}
            />
          ))}
        </div>
      )}
    </>
  );
}
