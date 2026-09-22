/**
 * 공통 컴포넌트 C1~C8 — 와이어프레임 Components.dc.html 대응.
 *
 * 규칙: 상태는 색 단독으로 전달하지 않는다. 항상 텍스트 라벨을 함께 렌더한다.
 * (교사 사용자 40대 중후반 + 색약 대응)
 */
import { Link } from "react-router";
import type {
  ConsentState,
  Institution,
  InstitutionType,
  ValidationStatus,
} from "@/lib/types";

export { PipelineStepper } from "./PipelineStepper";
export type { StageState, PipelineStepperProps } from "./PipelineStepper";

/* ── C1 판정 배지 ───────────────────────────────────── */

const VALIDATION_LABEL: Record<ValidationStatus, string> = {
  PASS: "PASS · 통과",
  REVIEW: "REVIEW · 확인 필요",
  BLOCK: "BLOCK · 멈춤",
};

const VALIDATION_CLASS: Record<ValidationStatus, string> = {
  PASS: "text-pass bg-passsoft",
  REVIEW: "text-review bg-reviewsoft",
  BLOCK: "text-block bg-blocksoft",
};

export function ValidationBadge({ status }: { status: ValidationStatus }) {
  return (
    <span
      className={`inline-flex items-center rounded px-2 py-0.5 text-[13px] font-semibold tracking-tight ${VALIDATION_CLASS[status]}`}
    >
      {VALIDATION_LABEL[status]}
    </span>
  );
}

/* ── C2 확신도 경고 ─────────────────────────────────── */

/**
 * 확인이 필요한 이유를 알려준다.
 *
 * 예전에는 confidence 를 퍼센트로 함께 보여줬는데 지웠다. 모델 점수가 같은 입력에도
 * 크게 흔들려(`AI/matching/config.py`) AI 쪽도 자동 확정 판단에서 이 값을 뺐는데,
 * 화면에 숫자가 남아 있으면 교사가 그것을 판단 근거로 삼게 된다.
 */
export function ConfidenceWarning({
  title = "확인 필요",
  message = "이 아이가 맞는지 확인해주세요",
  tone = "review",
}: {
  title?: string;
  message?: string;
  tone?: "review" | "block";
}) {
  const palette =
    tone === "block"
      ? "border-block/40 bg-blocksoft text-block"
      : "border-review/40 bg-reviewsoft text-review";

  return (
    <div className={`flex items-start gap-2 rounded border px-3 py-2 text-[15px] ${palette}`}>
      <span aria-hidden className="font-bold leading-6">
        !
      </span>
      <p className="leading-6">
        <b className="font-semibold">{title}</b> · {message}
      </p>
    </div>
  );
}

/* ── C3 강조 플래그 ─────────────────────────────────── */

/** REVIEW 사유가 된 문장을 요약 본문 안에서 그대로 하이라이트한다. */
export function FlaggedText({
  content,
  span,
}: {
  content: string;
  span?: string;
}) {
  if (!span || !content.includes(span)) {
    return <p className="leading-7 text-ink">{content}</p>;
  }
  const [before, ...rest] = content.split(span);
  return (
    <p className="leading-7 text-ink">
      {before}
      <mark className="bg-reviewsoft text-review underline decoration-review/50 decoration-2 underline-offset-2">
        {span}
      </mark>
      {rest.join(span)}
    </p>
  );
}

/**
 * 매칭 판정의 근거가 된 구간을 본문 안에서 하이라이트한다.
 *
 * FlaggedText 와 달리 **문자 인덱스**를 받는다 — AI 가 `[{start, end}]` 형태로
 * 주기 때문이다(`AI/matching/nodes.py` 의 `_evidence_for`). 구간이 여러 개일 수 있고,
 * 모델이 인용한 위치라 본문과 글자가 정확히 같지 않을 수도 있어 부분 문자열 검색으로는
 * 찾을 수 없다.
 *
 * 색은 accent 를 쓴다. REVIEW 하이라이트(review 색)는 "여기가 문제다" 라는 뜻인데,
 * 이쪽은 "여기를 보고 판단했다" 라서 의미가 반대다.
 */
export function EvidenceText({
  content,
  spans,
}: {
  content: string;
  spans?: { start: number; end: number }[];
}) {
  const valid = (spans ?? [])
    .filter((s) => s.end > s.start && s.start < content.length)
    .sort((a, b) => a.start - b.start);

  if (valid.length === 0) {
    return <p className="leading-7 text-ink">{content}</p>;
  }

  const parts: React.ReactNode[] = [];
  let cursor = 0;

  valid.forEach((span, i) => {
    // 구간이 겹치거나 범위를 벗어나도 본문이 깨지지 않게 잘라 맞춘다.
    const start = Math.max(cursor, span.start);
    const end = Math.min(content.length, Math.max(start, span.end));
    if (end <= start) return;

    if (start > cursor) parts.push(content.slice(cursor, start));
    parts.push(
      <mark
        key={i}
        className="rounded bg-accentsoft px-0.5 text-accentink underline decoration-accent/40 decoration-2 underline-offset-2"
      >
        {content.slice(start, end)}
      </mark>,
    );
    cursor = end;
  });

  if (cursor < content.length) parts.push(content.slice(cursor));

  return <p className="leading-7 text-ink">{parts}</p>;
}

/* ── C4 근거 출처 칩 ────────────────────────────────── */

export function EvidenceChip({
  date,
  label,
  institution,
  href,
}: {
  date: string;
  label: string;
  /** 어느 기관 기록인지 — Gate 2 승인자가 반드시 확인해야 한다 */
  institution?: Institution;
  href?: string;
}) {
  const body = (
    <span className="inline-flex items-center gap-2 rounded border border-line bg-surface px-2.5 py-1.5 text-[14px] text-ink2 hover:border-accent hover:text-accentink">
      {institution ? <InstitutionChip institution={institution} size="xs" /> : null}
      <span className="tabular-nums">{date}</span>
      <span className="text-muted">·</span>
      <span>{label}</span>
      <span aria-hidden className="text-muted">
        ›
      </span>
    </span>
  );
  return href ? <Link to={href}>{body}</Link> : body;
}

/* ── C5 큐 카드 ─────────────────────────────────────── */

export function QueueCard({
  title,
  count,
  href,
  tone = "neutral",
  description,
}: {
  title: string;
  count: number;
  href: string;
  tone?: "neutral" | "human" | "block";
  description?: string;
}) {
  const toneClass =
    tone === "human"
      ? "border-human/40 bg-humansoft"
      : tone === "block"
        ? "border-block/40 bg-blocksoft"
        : "border-line bg-surface";
  const countClass =
    tone === "human" ? "text-human" : tone === "block" ? "text-block" : "text-accentink";
  return (
    <Link
      to={href}
      className={`tap flex flex-col justify-between gap-2 rounded border p-4 transition-colors hover:border-accent ${toneClass}`}
    >
      <span className="text-[15px] font-semibold text-ink">{title}</span>
      <span className={`text-3xl font-bold tabular-nums ${countClass}`}>
        {count}
        <span className="ml-1 text-[15px] font-medium text-muted">건</span>
      </span>
      {description ? <span className="text-[13px] text-muted">{description}</span> : null}
    </Link>
  );
}

/* ── C6 기관 칩 ─────────────────────────────────────── */

const INST_LABEL: Record<InstitutionType, string> = {
  school: "학교",
  center: "센터",
  assistant: "활동지원사",
};
const INST_CLASS: Record<InstitutionType, string> = {
  school: "text-instschool bg-instschoolsoft",
  center: "text-instcenter bg-instcentersoft",
  assistant: "text-instassistant bg-instassistantsoft",
};

export function InstitutionChip({
  institution,
  size = "sm",
  withName = false,
}: {
  institution: Institution;
  size?: "xs" | "sm";
  withName?: boolean;
}) {
  const pad = size === "xs" ? "px-1.5 py-0 text-[12px]" : "px-2 py-0.5 text-[13px]";
  return (
    <span className="inline-flex items-center gap-1.5">
      <span
        className={`inline-flex shrink-0 items-center rounded font-semibold ${pad} ${INST_CLASS[institution.type]}`}
      >
        {INST_LABEL[institution.type]}
      </span>
      {withName ? <span className="text-ink">{institution.name}</span> : null}
    </span>
  );
}

/* ── C7 동의 상태 표시 ─────────────────────────────── */

const CONSENT_LABEL: Record<ConsentState, string> = {
  granted: "동의됨",
  not_granted: "미동의",
  revoked: "철회됨",
};
const CONSENT_CLASS: Record<ConsentState, string> = {
  granted: "text-pass",
  not_granted: "text-muted",
  revoked: "text-block",
};

export function ConsentStatus({ state }: { state: ConsentState }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 text-[14px] font-medium ${CONSENT_CLASS[state]}`}
    >
      <span aria-hidden>●</span>
      {CONSENT_LABEL[state]}
    </span>
  );
}

/* ── C8 되돌릴 수 없음 경고 ─────────────────────────── */

export function IrreversibleWarning({
  children = "발송하면 되돌릴 수 없습니다. 받는 기관에게 바로 전달됩니다.",
}: {
  children?: React.ReactNode;
}) {
  return (
    <div className="flex items-start gap-2 rounded border border-human/50 bg-humansoft px-3 py-2.5 text-[15px] text-human">
      <span aria-hidden className="font-bold leading-6">
        !
      </span>
      <p className="leading-6 font-medium">{children}</p>
    </div>
  );
}

/* ── 공통 레이아웃 조각 ─────────────────────────────── */

export function PageHeader({
  title,
  description,
  right,
}: {
  title: string;
  description?: string;
  right?: React.ReactNode;
}) {
  return (
    <header className="mb-6 flex flex-wrap items-end justify-between gap-3 border-b border-line pb-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-ink">{title}</h1>
        {description ? (
          <p className="mt-1 text-[15px] text-muted">{description}</p>
        ) : null}
      </div>
      {right}
    </header>
  );
}

export function Card({
  children,
  className = "",
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <section className={`rounded border border-line bg-surface p-5 ${className}`}>
      {children}
    </section>
  );
}

export function EmptyState({
  icon = "✉",
  title,
  description,
}: {
  icon?: string;
  title: string;
  description?: string;
}) {
  return (
    <div className="flex flex-col items-center gap-2 rounded border border-dashed border-line2 bg-surface px-6 py-14 text-center">
      <span aria-hidden className="text-2xl text-muted">
        {icon}
      </span>
      <p className="text-[16px] font-semibold text-ink2">{title}</p>
      {description ? (
        <p className="max-w-md text-[14px] leading-6 text-muted">{description}</p>
      ) : null}
    </div>
  );
}

export function Note({ children }: { children: React.ReactNode }) {
  return (
    <p className="rounded bg-surface2 px-3 py-2 text-[14px] leading-6 text-ink2">
      {children}
    </p>
  );
}
