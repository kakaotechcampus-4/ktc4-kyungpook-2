/**
 * C9 파이프라인 스테퍼 — 업로드 → Gate 직전까지 자동 진행 상황을 도식으로 보여준다.
 *
 * 규칙: 상태는 색 단독으로 전달하지 않는다. 원 안 아이콘 + 하단 라벨 + 상단 요약 문장을 함께 렌더한다.
 *
 * 멈춤은 두 가지이고 서로 다르다.
 * - waiting (사람 대기) — 사람이 골라야 진행된다. 매칭 확인 · 1차 검토 등. 실패가 아니다.
 * - failed  (실패)      — 시스템 오류. 재시도하면 된다.
 */

export type StageState = "done" | "active" | "pending" | "waiting" | "failed";

export interface PipelineStepperProps {
  stages: string[];
  /**
   * 단계별 상태를 직접 준다. 주면 currentIndex · halt · gateIndex 는 무시한다.
   * 파일 하나에 기록이 여러 건이라 단계마다 상태가 섞일 때 쓴다 (`stageStatesOf`).
   */
  states?: StageState[];
  /** 현재 진행 중(또는 도달한) 단계 인덱스. 이보다 앞 단계는 done, 뒤는 pending. */
  currentIndex?: number;
  /** 현재 단계가 멈춘 이유. 없으면 진행 중이다 */
  halt?: "waiting" | "failed";
  /** 도달하면 항상 사람 대기가 되는 단계 인덱스 (보통 마지막 단계) */
  gateIndex?: number;
  className?: string;
}

function resolveStates({
  stages,
  states,
  currentIndex = 0,
  halt,
  gateIndex,
}: PipelineStepperProps): StageState[] {
  if (states) return states;
  return stages.map((_, i) => {
    if (i < currentIndex) return "done";
    if (i > currentIndex) return "pending";
    if (halt) return halt;
    return gateIndex !== undefined && i >= gateIndex ? "waiting" : "active";
  });
}

function summaryOf(stages: string[], states: StageState[]): string {
  const failed = states.indexOf("failed");
  if (failed !== -1) return `${stages[failed]} 실패 · 다시 시도해야 합니다`;
  const waiting = states.indexOf("waiting");
  if (waiting !== -1) return `${stages[waiting]} · 사람 확인을 기다리는 중`;
  const active = states.indexOf("active");
  if (active !== -1) return `${stages[active]} 진행 중`;
  return states.every((s) => s === "done") ? "처리 완료" : `${stages[0]} 대기 중`;
}

const CIRCLE_CLASS: Record<StageState, string> = {
  done: "bg-accent text-white",
  waiting: "border-2 border-human bg-humansoft text-human",
  failed: "border-2 border-block bg-blocksoft text-block",
  active: "border-2 border-accent bg-accentsoft text-accentink animate-pulse",
  pending: "border border-line bg-surface2 text-muted",
};

function iconOf(state: StageState, i: number): string {
  if (state === "done") return "✓";
  if (state === "waiting") return "‖";
  if (state === "failed") return "✕";
  return String(i + 1);
}

/** 선이 채워지는 기준 — 앞 단계를 지나 여기까지 온 것 */
const reached = (s: StageState) => s !== "pending";

export function PipelineStepper(props: PipelineStepperProps) {
  const { stages, className = "" } = props;
  const states = resolveStates(props);

  return (
    <div className={className}>
      <p className="mb-3 text-[14px] font-medium text-ink2">{summaryOf(stages, states)}</p>
      <ol className="flex items-start">
        {stages.map((s, i) => {
          const state = states[i];
          return (
            <li key={s} className="flex flex-1 flex-col items-center last:flex-none">
              <div className="flex w-full items-center">
                {i > 0 ? (
                  <span
                    aria-hidden
                    className={`h-0.5 flex-1 ${reached(state) ? "bg-accent" : "bg-line"}`}
                  />
                ) : null}
                <span
                  aria-hidden
                  className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-[13px] font-bold ${CIRCLE_CLASS[state]}`}
                >
                  {iconOf(state, i)}
                </span>
                {i < stages.length - 1 ? (
                  <span
                    aria-hidden
                    className={`h-0.5 flex-1 ${reached(states[i + 1]) ? "bg-accent" : "bg-line"}`}
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

const DOT_CLASS: Record<StageState, string> = {
  done: "bg-accent",
  active: "bg-accentsoft ring-2 ring-accent animate-pulse",
  waiting: "bg-humansoft ring-2 ring-human",
  failed: "bg-block",
  pending: "bg-line",
};

/**
 * 목록 한 줄에 들어가는 작은 스테퍼. 라벨이 없으므로 aria-label 로 단계별 상태를 읽어준다.
 * 색만으로 상태를 전달하지 않도록 옆에 건수 요약(교사 확인 · 실패)을 반드시 함께 둔다.
 */
export function StageDots({ stages, states }: { stages: string[]; states: StageState[] }) {
  const word: Record<StageState, string> = {
    done: "완료",
    active: "진행 중",
    waiting: "사람 대기",
    failed: "실패",
    pending: "대기 전",
  };
  return (
    <span
      role="img"
      aria-label={stages.map((s, i) => `${s} ${word[states[i]]}`).join(", ")}
      className="inline-flex items-center gap-1.5"
    >
      {states.map((state, i) => (
        <span
          key={stages[i]}
          title={`${stages[i]} · ${word[state]}`}
          className={`inline-block size-2.5 rounded-full ${DOT_CLASS[state]}`}
        />
      ))}
    </span>
  );
}
