export type StageState = "done" | "active" | "pending" | "waiting" | "failed";

export interface PipelineStepperProps {
  stages: string[];
  states?: StageState[];
  currentIndex?: number;
  halt?: "waiting" | "failed";
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
