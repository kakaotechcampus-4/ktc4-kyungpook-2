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

export function ConfidenceWarning({
  confidence,
  message = "이 아이가 맞는지 확인해주세요",
}: {
  confidence?: number | null;
  message?: string;
}) {
  return (
    <div className="flex items-start gap-2 rounded border border-review/40 bg-reviewsoft px-3 py-2 text-[15px] text-review">
      <span aria-hidden className="font-bold leading-6">
        !
      </span>
      <p className="leading-6">
        <b className="font-semibold">
          확신도 낮음
          {typeof confidence === "number" ? ` · ${Math.round(confidence * 100)}%` : ""}
        </b>{" "}
        · {message}
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
