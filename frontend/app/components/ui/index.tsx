import { useEffect, useId } from "react";
import { Link } from "react-router";
import type { LucideIcon } from "lucide-react";
import { CheckCircle2, CircleDashed, Mail } from "lucide-react";
import type {
  ConsentState,
  Institution,
  InstitutionType,
  ValidationStatus,
} from "@/lib/types";

import { Busy } from "./Loading";
export { PipelineStepper, StageDots } from "./PipelineStepper";
export { Busy, FullPageLoading, NavigationProgress, Spinner, useNavigating } from "./Loading";
export { ReloginButton } from "./ReloginButton";
export type { StageState, PipelineStepperProps } from "./PipelineStepper";

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

export function EvidenceText({
  content,
  spans,
}: {
  content: string;
  spans?: { start: number; end: number }[];
}) {
  const chars = Array.from(content);
  const cut = (from: number, to: number) => chars.slice(from, to).join("");

  const valid = (spans ?? [])
    .filter((s) => s.end > s.start && s.start < chars.length)
    .sort((a, b) => a.start - b.start);

  if (valid.length === 0) {
    return <p className="leading-7 text-ink">{content}</p>;
  }

  const parts: React.ReactNode[] = [];
  let cursor = 0;

  valid.forEach((span, i) => {
    const start = Math.max(cursor, span.start);
    const end = Math.min(chars.length, Math.max(start, span.end));
    if (end <= start) return;

    if (start > cursor) parts.push(cut(cursor, start));
    parts.push(
      <mark
        key={i}
        className="rounded bg-accentsoft px-0.5 text-accentink underline decoration-accent/40 decoration-2 underline-offset-2"
      >
        {cut(start, end)}
      </mark>,
    );
    cursor = end;
  });

  if (cursor < chars.length) parts.push(cut(cursor, chars.length));

  return <p className="leading-7 text-ink">{parts}</p>;
}

export function EvidenceChip({
  date,
  label,
  institution,
  href,
}: {
  date: string;
  label: string;
  institution?: Institution;
  href?: string;
}) {
  const body = (
    <span
      className={`inline-flex items-center gap-2 rounded border border-line bg-surface px-2.5 py-1.5 text-[14px] text-ink2 ${
        href ? "hover:border-accent hover:text-accentink" : ""
      }`}
    >
      {institution ? <InstitutionChip institution={institution} size="xs" /> : null}
      <span className="tabular-nums">{date}</span>
      <span className="text-muted">·</span>
      <span>{label}</span>
      {href ? (
        <span aria-hidden className="text-muted">
          ›
        </span>
      ) : null}
    </span>
  );
  return href ? <Link to={href}>{body}</Link> : body;
}

export function QueueCard({
  title,
  count,
  href,
  tone = "neutral",
  description,
}: {
  title: string;
  count: number | null;
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
      className={`tap flex flex-col justify-between gap-2 rounded-[20px] border p-5 transition-colors hover:border-accent ${toneClass}`}
    >
      <span className="text-[15px] font-semibold text-ink">{title}</span>
      {count === null ? (
        <span className="text-[17px] font-semibold text-muted">준비 중</span>
      ) : (
        <span className={`text-3xl font-bold tabular-nums ${countClass}`}>
          {count}
          <span className="ml-1 text-[15px] font-medium text-muted">건</span>
        </span>
      )}
      {description ? <span className="text-[13px] text-muted">{description}</span> : null}
    </Link>
  );
}

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
    <span className="inline-flex min-w-0 max-w-full items-center gap-1.5">
      <span
        className={`inline-flex shrink-0 items-center rounded font-semibold ${pad} ${INST_CLASS[institution.type]}`}
      >
        {INST_LABEL[institution.type]}
      </span>
      {withName ? (
        <span className="min-w-0 truncate text-ink">{institution.name}</span>
      ) : null}
    </span>
  );
}

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

const CONFIRM_TONE = {
  human: "bg-ink text-paper",
  neutral: "bg-ink2 text-white",
  block: "bg-block text-white",
  accent: "bg-ink text-paper",
} as const;

export function ConfirmDialog({
  title,
  children,
  warning,
  confirmLabel,
  tone = "human",
  busy = false,
  confirmDisabled = false,
  onCancel,
  onConfirm,
}: {
  title: string;
  children?: React.ReactNode;
  warning?: React.ReactNode;
  confirmLabel: string;
  tone?: keyof typeof CONFIRM_TONE;
  busy?: boolean;
  confirmDisabled?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const titleId = useId();

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape" && !busy) onCancel();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onCancel]);

  return (
    <div
      role="dialog"
      aria-modal
      aria-labelledby={titleId}
      className="fixed inset-0 z-[60] flex items-center justify-center bg-ink/40 px-4"
    >
      <div className="flex w-full max-w-[440px] flex-col gap-2.5 rounded-md bg-surface p-5 shadow-[0_12px_32px_rgba(22,28,27,0.18)]">
        <p id={titleId} className="text-[18px] font-bold">
          {title}
        </p>
        {children ? <div className="text-[15px] leading-7 text-ink2">{children}</div> : null}
        {warning ? <p className="text-[15px] font-bold text-ink2">{warning}</p> : null}
        <div className="mt-2 flex justify-end gap-2">
          <button
            type="button"
            autoFocus
            onClick={onCancel}
            disabled={busy}
            className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2 disabled:opacity-60"
          >
            취소
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={busy || confirmDisabled}
            className={`tap rounded px-5 text-[15px] font-bold hover:brightness-95 disabled:opacity-60 ${CONFIRM_TONE[tone]}`}
          >
            {busy ? <Busy>처리 중…</Busy> : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

export function PageHeader({
  eyebrow,
  title,
  description,
  right,
}: {
  eyebrow?: React.ReactNode;
  title: string;
  description?: string;
  right?: React.ReactNode;
}) {
  return (
    <header className="mb-7 flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        {eyebrow ? (
          <p className="mb-1 text-[15px] text-muted">{eyebrow}</p>
        ) : null}
        <h1 className="text-[clamp(24px,3.4vw,32px)] leading-[1.25] font-extrabold tracking-[-0.03em] text-balance text-ink">
          {title}
        </h1>
        {description ? (
          <p className="mt-1.5 text-[15px] text-ink2">{description}</p>
        ) : null}
      </div>
      {right}
    </header>
  );
}

export function PanelHead({
  title,
  note,
  right,
  id,
}: {
  title: string;
  note?: React.ReactNode;
  right?: React.ReactNode;
  id?: string;
}) {
  return (
    <div className="mb-4 flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
      <div className="min-w-0">
        <h2 id={id} className="text-[19px] font-extrabold tracking-[-0.02em] text-ink">
          {title}
        </h2>
        {note ? <p className="mt-1 text-[14px] leading-6 text-muted">{note}</p> : null}
      </div>
      {right}
    </div>
  );
}

export function BigNum({
  value,
  label,
  tone = "ink",
}: {
  value: number | null;
  label: string;
  tone?: "ink" | "accent" | "review";
}) {
  const color =
    value === null || value === 0
      ? "text-line2"
      : tone === "accent"
        ? "text-accentink"
        : tone === "review"
          ? "text-review"
          : "text-ink";
  return (
    <span className="flex shrink-0 flex-col items-end leading-none">
      <span className={`text-[26px] font-extrabold tabular-nums ${color}`}>
        {value === null ? "—" : value}
      </span>
      <span className="mt-1.5 text-[13px] font-normal text-muted">{label}</span>
    </span>
  );
}

export function Card({
  children,
  className = "",
  id,
}: {
  children: React.ReactNode;
  className?: string;
  id?: string;
}) {
  return (
    <section
      id={id}
      className={`rounded-[22px] border border-line/70 bg-surface p-6 shadow-[0_4px_18px_rgba(120,90,40,0.1)] ${className}`}
    >
      {children}
    </section>
  );
}

const EMPTY_ICON = {
  done: CheckCircle2,
  none: CircleDashed,
  mail: Mail,
} satisfies Record<string, LucideIcon>;

export function EmptyState({
  icon = "mail",
  title,
  description,
  children,
}: {
  icon?: keyof typeof EMPTY_ICON;
  title: string;
  description?: string;
  children?: React.ReactNode;
}) {
  const Icon = EMPTY_ICON[icon];
  return (
    <div className="flex flex-col items-center gap-2 rounded border border-dashed border-line2 bg-surface px-6 py-14 text-center">
      <Icon aria-hidden size={30} strokeWidth={1.5} className="text-line2" />
      <p className="text-[16px] font-semibold text-ink2">{title}</p>
      {description ? (
        <p className="max-w-md text-[14px] leading-6 text-muted">{description}</p>
      ) : null}
      {children}
    </div>
  );
}

export function StuckList({
  items,
}: {
  items: { label: string; count: number | null; href: string }[];
}) {
  const live = items.filter((i) => (i.count ?? 0) > 0);
  if (live.length === 0) return null;
  return (
    <ul className="mt-3 flex w-full max-w-sm flex-col gap-1.5">
      {live.map((i) => (
        <li key={i.href}>
          <Link
            to={i.href}
            className="tap flex items-center gap-3 rounded-[14px] bg-surface2 px-4 text-[15px] hover:brightness-[0.97]"
          >
            <span className="min-w-0 flex-1 truncate text-left font-semibold text-ink">
              {i.label}
            </span>
            <span className="shrink-0 text-[15px] font-extrabold text-ink tabular-nums">
              {i.count}건
            </span>
            <span aria-hidden className="shrink-0 text-muted">
              ›
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}

export function Note({ children }: { children: React.ReactNode }) {
  return (
    <p className="rounded bg-surface2 px-3 py-2 text-[14px] leading-6 text-ink2">
      {children}
    </p>
  );
}
