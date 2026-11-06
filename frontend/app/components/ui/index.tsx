/**
 * 공통 컴포넌트 C1~C9 — 와이어프레임 Components.dc.html 대응 (C9 확인 창은 그 뒤에 추가).
 *
 * 규칙: 상태는 색 단독으로 전달하지 않는다. 항상 텍스트 라벨을 함께 렌더한다.
 * (교사 사용자 40대 중후반 + 색약 대응)
 */
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
  // AI 의 인덱스는 코드포인트 기준이다. String.slice(UTF-16) 로 자르면 이모지 뒤부터
  // 한 칸씩 밀리므로 코드포인트 배열로 바꿔서 자른다 (AI/matching/schemas.py 의 EvidenceSpan).
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
    // 구간이 겹치거나 범위를 벗어나도 본문이 깨지지 않게 잘라 맞춘다.
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
  /** 없으면 눌리지 않는 칩이다 — 눌리는 것처럼 보이지 않게 화살표 · hover 를 뺀다 */
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

/* ── C5 큐 카드 ─────────────────────────────────────── */

export function QueueCard({
  title,
  count,
  href,
  tone = "neutral",
  description,
}: {
  title: string;
  /** null 이면 백엔드가 아직 없는 기능 — 숫자 대신 "준비 중"을 보인다 */
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

/* ── C9 확인 창 ─────────────────────────────────────── */

const CONFIRM_TONE = {
  human: "bg-ink text-paper",
  neutral: "bg-ink2 text-white",
  block: "bg-block text-white",
  accent: "bg-ink text-paper",
} as const;

/** 되돌릴 수 없는 결정 앞에서 한 번 더 묻는다. Esc · 취소로 닫는다 */
export function ConfirmDialog({
  title,
  children,
  warning,
  confirmLabel,
  tone = "human",
  busy = false,
  onCancel,
  onConfirm,
}: {
  title: string;
  children?: React.ReactNode;
  /** 굵게 따로 띄우는 한 줄. "되돌릴 수 없어요" 같은 것 */
  warning?: React.ReactNode;
  confirmLabel: string;
  tone?: keyof typeof CONFIRM_TONE;
  busy?: boolean;
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
      className="fixed inset-0 z-20 flex items-center justify-center bg-ink/40 px-4"
    >
      <div className="flex w-full max-w-[440px] flex-col gap-2.5 rounded-md bg-surface p-5 shadow-[0_12px_32px_rgba(22,28,27,0.18)]">
        <p id={titleId} className="text-[18px] font-bold">
          {title}
        </p>
        {children ? <div className="text-[15px] leading-7 text-ink2">{children}</div> : null}
        {warning ? <p className="text-[15px] font-bold text-ink2">{warning}</p> : null}
        <div className="mt-2 flex justify-end gap-2">
          {/* 엔터 한 번에 되돌릴 수 없는 결정이 나가지 않게 취소에 포커스를 둔다 */}
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
            disabled={busy}
            className={`tap rounded px-5 text-[15px] font-bold hover:brightness-95 disabled:opacity-60 ${CONFIRM_TONE[tone]}`}
          >
            {busy ? <Busy>처리 중…</Busy> : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ── 공통 레이아웃 조각 ─────────────────────────────── */

export function PageHeader({
  eyebrow,
  title,
  description,
  right,
}: {
  /** 제목 **위**에 올리는 짧은 맥락 한 줄 — 캔버스의 "10월 30일 (금)", "1 / 3", "3명 · 햇살초등학교" 자리 */
  eyebrow?: React.ReactNode;
  title: string;
  description?: string;
  right?: React.ReactNode;
}) {
  return (
    // 캔버스는 제목 아래에 선을 긋지 않는다 — 큰 글씨 자체가 구분선 노릇을 한다
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

/* ── 캔버스 조각 ─────────────────────────────────────
   카드 안의 제목 줄. 캔버스는 카드마다 19px/800 제목 하나와 오른쪽 조작 하나를 둔다. */

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

/**
 * 캔버스가 "셀 수 있는 것"에 쓰는 큰 숫자. 0 은 흐리게 두되 **숨기지 않는다**
 * (캔버스 10-kids 의 "0 은 0 으로" 규칙).
 */
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
  /** 다른 화면에서 `#id` 로 바로 내려오게 할 때 */
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

/** 빈 화면에 세울 그림. 이름으로 고른다 — 글자를 그림 자리에 넣지 않는다 */
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
  /** 비어 있는 **이유**와 그리로 가는 길. 없으면 안 그린다 */
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

/**
 * 빈 화면 밑에 세우는 "지금 어디에 걸려 있나" 목록.
 * 0 건인 줄은 그리지 않는다 — 할 일이 없는 곳을 가리키면 길을 더 헤맨다.
 */
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
