/**
 * C9 파이프라인 스테퍼 — 업로드 → Gate 직전까지 자동 진행 상황을 도식으로 보여준다.
 *
 * 규칙: 상태는 색 단독으로 전달하지 않는다. 원 안 아이콘 + 하단 라벨 + 상단 요약 문장을 함께 렌더한다.
 */

export type StageState = "done" | "active" | "pending";

export interface PipelineStepperProps {
  stages: string[];
  /** 현재 진행 중(또는 도달한) 단계 인덱스. 이보다 앞 단계는 done, 뒤는 pending. */
  currentIndex: number;
  /** 사람 승인이 필요해 자동 진행이 멈추는 단계 인덱스 (보통 마지막 단계) */
  gateIndex?: number;
  className?: string;
}

function stateOf(i: number, currentIndex: number): StageState {
  if (i < currentIndex) return "done";
  if (i === currentIndex) return "active";
  return "pending";
}

export function PipelineStepper({
  stages,
  currentIndex,
  gateIndex,
  className = "",
}: PipelineStepperProps) {
  const atGate = gateIndex !== undefined && currentIndex >= gateIndex;
  const summary = atGate
    ? `${stages[gateIndex!]} · 사람 승인을 기다리는 중`
    : `${stages[currentIndex]} 진행 중`;

  return (
    <div className={className}>
      <p className="mb-3 text-[14px] font-medium text-ink2">{summary}</p>
      <ol className="flex items-start">
        {stages.map((s, i) => {
          const state = stateOf(i, currentIndex);
          const isGate = gateIndex !== undefined && i === gateIndex;
          const isWaitingGate = isGate && state === "active";

          const circleClass =
            state === "done"
              ? "bg-accent text-white"
              : isWaitingGate
                ? "border-2 border-human bg-humansoft text-human"
                : state === "active"
                  ? "border-2 border-accent bg-accentsoft text-accentink"
                  : "border border-line bg-surface2 text-muted";

          return (
            <li key={s} className="flex flex-1 flex-col items-center last:flex-none">
              <div className="flex w-full items-center">
                {i > 0 ? (
                  <span
                    aria-hidden
                    className={`h-0.5 flex-1 ${i <= currentIndex ? "bg-accent" : "bg-line"}`}
                  />
                ) : null}
                <span
                  aria-hidden
                  className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-[13px] font-bold ${circleClass} ${
                    state === "active" ? "animate-pulse" : ""
                  }`}
                >
                  {state === "done" ? "✓" : isWaitingGate ? "‖" : i + 1}
                </span>
                {i < stages.length - 1 ? (
                  <span
                    aria-hidden
                    className={`h-0.5 flex-1 ${i < currentIndex ? "bg-accent" : "bg-line"}`}
                  />
                ) : null}
              </div>
              <span
                className={`mt-1.5 px-1 text-center text-[13px] ${
                  state === "pending" ? "text-muted" : "font-medium text-ink"
                }`}
              >
                {s}
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
