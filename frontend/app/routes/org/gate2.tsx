import { useState } from "react";
import { useLoaderData, useRevalidator } from "react-router";
import { NotReadyPage } from "@/components/org/NotReady";
import {
  Card,
  ConfirmDialog,
  ConsentStatus,
  EmptyState,
  EvidenceChip,
  InstitutionChip,
  IrreversibleWarning,
  Note,
  PageHeader,
} from "@/components/ui";
import { ApiError, decideGate2, getInsights, isReady } from "@/lib/api";
import type { Insight } from "@/lib/types";

/** BE 가 우리 기관이 승인 주체인 것만 준다 — 화면에서 기관으로 다시 거르지 않는다 */
export async function clientLoader() {
  return { mine: await getInsights("pending") };
}

type Decision = "approve" | "hold";
/** stale: 그새 다른 사람이 처리했거나 새 회차로 바뀌어 더 결정할 수 없다 */
type View = "review" | "approved" | "held" | "stale";

function Gate2Item({
  insight,
  initialView = "review",
  initialNotice = null,
  onResolved,
}: {
  insight: Insight;
  initialView?: View;
  initialNotice?: string | null;
  onResolved: (view: View, notice?: string) => void;
}) {
  const [view, setView] = useState<View>(initialView);
  const [confirming, setConfirming] = useState<Decision | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(initialNotice);
  const revalidator = useRevalidator();

  async function decide(decision: Decision) {
    setBusy(true);
    setError(null);
    try {
      await decideGate2(insight.id, { decision });
      const next = decision === "approve" ? "approved" : "held";
      setView(next);
      onResolved(next);
      await revalidator.revalidate();
    } catch (err) {
      const message = messageOf(err);
      setError(message);
      // 이미 처리됐거나 새 회차가 생긴 건이면 목록을 새로 받는다. 카드는 안내와 함께 남긴다
      if (isStale(err)) {
        setView("stale");
        onResolved("stale", message);
        await revalidator.revalidate();
      }
    } finally {
      setBusy(false);
      setConfirming(null);
    }
  }

  if (view === "approved") {
    return (
      <Card className="border-pass/40 bg-passsoft/50">
        <p className="mb-2 text-[16px] font-bold text-pass">승인 완료</p>
        <p className="mb-3 text-[15px] leading-7 text-ink2">
          이제 보호자가 <b className="font-semibold">{insight.childName}</b> 의 분석 결과를 볼 수
          있습니다. 어느 기관과 나눌지는 보호자가 정합니다.
        </p>
        <Note>승인은 되돌릴 수 없습니다.</Note>
      </Card>
    );
  }

  if (view === "held") {
    return (
      <Card className="border-line2">
        <p className="mb-2 text-[16px] font-bold text-ink2">보류됨</p>
        <p className="text-[15px] leading-7 text-muted">
          이 결과는 보호자에게 보이지 않습니다. 보류는 되돌릴 수 없고, 다음 분석 결과가 만들어지면
          새로 검토할 수 있습니다.
        </p>
      </Card>
    );
  }

  if (view === "stale") {
    return (
      <Card className="border-line2">
        <p className="mb-2 text-[16px] font-bold text-ink2">
          {insight.childName} · {insight.period}
        </p>
        <p role="alert" className="text-[15px] leading-7 text-muted">
          {error}
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

      {/* BE 는 지금 항상 true 다 — 본문에 다른 아이 이름이 없다는 보장이 없다 */}
      {insight.needsReview ? (
        <p role="note" className="mb-3 rounded bg-reviewsoft px-3 py-2 text-[14px] text-review">
          다른 아이 이름이나 사실과 다른 문장이 섞여 있지 않은지 끝까지 읽어 본 뒤 승인해 주세요.
        </p>
      ) : null}

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

      {/* 읽기 전용 참고. 수신 기관은 기관이 고르지 않고 승인 뒤 보호자가 정한다 (#206) */}
      {insight.targets.length > 0 ? (
        <section className="mb-4">
          <h3 className="mb-1 text-[17px] font-bold">이 아이가 다니는 다른 기관</h3>
          <p className="mb-3 text-[14px] text-muted">
            승인하면 보호자가 이 결과를 어느 기관과 나눌지 정합니다. 선생님이 고르지 않습니다.
          </p>
          <ul className="flex flex-col gap-2">
            {insight.targets.map((t) => (
              <li
                key={t.institution.id}
                className="tap flex items-center gap-3 rounded border border-line px-3"
              >
                <InstitutionChip institution={t.institution} withName />
                <span className="ml-auto">
                  <ConsentStatus state={t.consent} />
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <div className="mb-4">
        <IrreversibleWarning>
          승인하면 보호자에게 바로 보입니다. 승인과 보류 모두 되돌릴 수 없습니다.
        </IrreversibleWarning>
      </div>

      <div className="flex flex-wrap gap-2">
        <button
          onClick={() => setConfirming("approve")}
          disabled={busy}
          className="tap rounded bg-human px-5 text-[16px] font-semibold text-white hover:brightness-95 disabled:opacity-60"
        >
          승인
        </button>
        <button
          onClick={() => setConfirming("hold")}
          disabled={busy}
          className="tap rounded border border-line2 px-4 text-[16px] font-semibold text-ink2 hover:bg-surface2 disabled:opacity-60"
        >
          보류
        </button>
      </div>

      {error ? (
        <p role="alert" className="mt-3 text-[14px] text-block">
          {error}
        </p>
      ) : null}

      {/* 승인·보류 모두 되돌릴 수 없어 한 번 더 묻는다 */}
      {confirming ? (
        <ConfirmDialog
          title={`${insight.childName} 분석 결과를 ${confirming === "approve" ? "승인할까요?" : "보류할까요?"}`}
          warning="되돌릴 수 없습니다."
          confirmLabel={confirming === "approve" ? "승인하기" : "보류하기"}
          tone={confirming === "approve" ? "human" : "neutral"}
          busy={busy}
          onCancel={() => setConfirming(null)}
          onConfirm={() => decide(confirming)}
        >
          {confirming === "approve"
            ? "승인하면 보호자에게 바로 보입니다."
            : "보류하면 이 결과는 보호자에게 보이지 않습니다."}
        </ConfirmDialog>
      ) : null}
    </Card>
  );
}

/** 다시 눌러도 결과가 같은 오류 — 카드를 결정할 수 없는 상태로 바꾼다 */
function isStale(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    (err.code === "INSIGHT_ALREADY_REVIEWED" ||
      err.code === "INSIGHT_SUPERSEDED" ||
      err.code === "INSIGHT_NOT_PRIMARY_SOURCE" ||
      err.status === 404)
  );
}

function messageOf(err: unknown): string {
  if (!(err instanceof ApiError)) return "네트워크 연결을 확인하고 다시 시도해 주세요.";
  if (err.code === "INSIGHT_ALREADY_REVIEWED") {
    return "이미 승인 또는 보류된 결과입니다. 목록을 새로 불러왔습니다.";
  }
  if (err.code === "INSIGHT_SUPERSEDED") {
    return "같은 기간의 더 새 분석 결과가 만들어졌습니다. 목록에서 새 결과를 검토해 주세요.";
  }
  if (err.code === "INSIGHT_NOT_PRIMARY_SOURCE") {
    return "우리 기관이 승인하는 결과가 아닙니다. 근거를 가장 많이 낸 기관이 검토합니다.";
  }
  if (err.status === 404) return "더 이상 볼 수 없는 결과입니다. 목록을 새로 불러왔습니다.";
  if (err.status === 401) return "로그인이 풀렸습니다. 다시 로그인한 뒤 시도해 주세요.";
  if (err.status === 403) return "보안 확인에 실패했습니다. 새로고침한 뒤 다시 시도해 주세요.";
  return "처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요.";
}

/** 백엔드에 아직 없으면 mock 데이터 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function Gate2Route() {
  if (!isReady("insights")) {
    return (
      <NotReadyPage
        title="공유 전 검토"
        description="근거 기록을 제공한 선생님이 보호자에게 보여도 되는지 판단합니다"
      />
    );
  }
  return <Gate2Page />;
}

function Gate2Page() {
  const { mine } = useLoaderData<typeof clientLoader>();
  /**
   * 승인/보류하면 revalidate 로 사이드바 배지가 갱신되면서 이 항목은 `mine`(pending
   * 목록) 에서 빠진다 — "완료" 카드가 화면에서 사라지지 않도록 이번 방문 동안만
   * 로컬로 기억해서 함께 보여준다.
   */
  const [resolved, setResolved] = useState<
    Record<string, { view: View; insight: Insight; notice?: string }>
  >({});
  const pendingItems = mine.filter((i) => !resolved[i.id]);
  const visible = [...pendingItems, ...Object.values(resolved).map((r) => r.insight)];

  return (
    <>
      <PageHeader
        title="공유 전 검토"
        description="근거 기록을 제공한 선생님이 보호자에게 보여도 되는지 판단합니다"
      />
      {visible.length === 0 ? (
        <EmptyState
          icon="✓"
          title="검토할 분석 결과가 없습니다"
          description="우리 기관 기록이 근거가 된 관찰·분석 결과가 생기면 여기에 표시됩니다."
        />
      ) : (
        <div className="flex flex-col gap-6">
          {visible.map((i) => (
            <Gate2Item
              key={i.id}
              insight={i}
              initialView={resolved[i.id]?.view ?? "review"}
              initialNotice={resolved[i.id]?.notice ?? null}
              onResolved={(view, notice) =>
                setResolved((r) => ({ ...r, [i.id]: { view, insight: i, notice } }))
              }
            />
          ))}
        </div>
      )}
    </>
  );
}
