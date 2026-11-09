import { useEffect, useRef, useState } from "react";
import {
  Check,
  ChevronRight,
  FileCheck,
  FileText,
  Info,
  Layers,
  Link2,
  PenLine,
  ScrollText,
  Send,
  ShieldCheck,
  Sparkles,
  TriangleAlert,
  Upload,
  UserRound,
  UserSearch,
  X,
} from "lucide-react";
import { Link, useLoaderData, useRevalidator } from "react-router";
import { Busy, ConfirmDialog } from "@/components/ui";
import { useOrgLive } from "@/components/org/Shell";
import {
  getAiActivity,
  getChildren,
  getFileProgress,
  getPipeline,
  getUploadedFiles,
  UPLOAD_EXTENSIONS,
  uploadRawRecords,
  type UploadedFile,
} from "@/lib/api";
import { fileProgressOf } from "@/lib/pipeline";
import type { AiActivity, FileProgress, Pipeline, PipelineRow, PipelineTurn, UploadResult } from "@/lib/types";
import { TourButton } from "@/components/Tour";
import { usePolling } from "@/lib/usePolling";
import { useAiProgress, type AiPhase } from "@/lib/aiProgress";

/**
 * 대시보드 — 선생님 차례 · 기록 올리기 · 진행 현황 · 오늘 AI가 한 일 (시안 screenDash).
 * 단계 판정은 서버(O-80)가 하고 화면은 받은 그대로 그린다. 10초마다 다시 받는다(탭이 숨으면 멈춤).
 * 요약은 자동으로 시작하므로(#369) [요약] 버튼이 없다.
 */
export async function clientLoader() {
  const [pipeline, activity, files, children] = await Promise.all([
    // 진행 현황 · AI 멈춤은 셸(components/org/Shell)이 받아 10초마다 다시 받는다. 이 값은 셸이 진행 현황을
    // 한 번도 못 받았을 때 쓰고, 여기서 실패하면 지금처럼 오류 화면이다. AI 멈춤은 셸 값만 쓴다
    getPipeline(),
    getAiActivity(),
    getFileProgress(),
    // 시작하기 체크리스트용. 이미 닫은 기관은 받지 않는다. 못 받아도 대시보드는 띄운다
    readClosed() ? Promise.resolve(null) : getChildren().catch(() => null),
  ]);
  return {
    pipeline,
    activity,
    files,
    kids: children ? children.length : null,
    guardians: children ? children.filter((c) => c.consent === "granted").length : null,
  };
}

const POLL_MS = 10_000;

const STEPS = ["아이 매칭", "내용 검증", "일지 요약", "요약 검토", "주간 리포트", "리포트 승인"] as const;
/** 진행 현황 단계 칸 오른쪽 끝에 붙는 아이콘. STEPS 와 같은 순서 */
const STEP_ICONS = [UserSearch, ShieldCheck, ScrollText, FileCheck, Layers, Send] as const;
const STEP_DESC = [
  "AI가 파일 이름과 일지 속 이름을 아동 명부와 맞춰 누구의 기록인지 찾아요",
  "AI가 개인정보·진단명이 있으면 막고, 다른 아이 이름 같은 표현은 표시해요",
  "내용 검증이 끝나면 AI가 그날 기록을 바로 요약해요",
  "선생님이 요약 문장을 읽고 고친 뒤 승인해요",
  "한 주(월~일) 동안 승인한 기록을 모아 AI가 되풀이되는 행동을 찾아요",
  "선생님이 AI가 쓴 주간 리포트를 읽고 승인하면 보호자와 동의한 기관이 봐요",
];

const WD = "일월화수목금토";
function md(d: string): string {
  const [, m, day] = d.split("-");
  return `${+m}월 ${+day}일`;
}
function today(): string {
  const d = new Date();
  return `${d.getMonth() + 1}월 ${d.getDate()}일 (${WD[d.getDay()]})`;
}

export default function DashboardPage() {
  const initial = useLoaderData<typeof clientLoader>();
  // 진행 현황 · AI 멈춤(O-82, AI 차례 줄을 "멈춤" 으로)은 셸이 10초마다 받는 값을 같이 쓴다 — 같은 요청을 두 번 보내지 않게
  const live = useOrgLive();
  const pipeline: Pipeline = live.pipeline ?? initial.pipeline;
  const aiDown = live.aiDown;
  const [activity, setActivity] = useState<AiActivity>(initial.activity);
  const [files, setFiles] = useState<FileProgress[]>(initial.files);

  // loader 가 다시 돌면(다른 화면에서 처리하고 돌아옴 · revalidate) 새 값으로 바꾼다 — 다음 폴링까지 옛 숫자가 남지 않게
  useEffect(() => {
    setActivity(initial.activity);
    setFiles(initial.files);
  }, [initial]);

  /** 이 화면에만 있는 값. 10초마다 */
  async function refreshOwn() {
    const [a, f] = await Promise.all([getAiActivity(), getFileProgress()]);
    setActivity(a);
    setFiles(f);
  }
  usePolling(refreshOwn, POLL_MS, true, false);

  /** 기록을 올린 뒤 — 진행 현황(셸)과 이 화면 값을 함께 바로 다시 받는다 */
  async function refreshAll() {
    await Promise.all([live.refresh(), refreshOwn()]);
  }

  const turn = pipeline.turn;
  const total = turn.confirm + turn.blocked + turn.review + turn.approve + turn.requests;

  return (
    <div className="grid gap-5">
      <header>
        <p className="mb-1 text-[15px] text-muted">{today()}</p>
        <div className="flex items-center gap-2.5">
          <h1 className="text-[clamp(24px,3.4vw,32px)] leading-[1.25] font-extrabold tracking-[-0.03em]">
            {total ? `오늘 선생님 차례 ${total}건` : "오늘 선생님 차례가 없어요"}
          </h1>
          <TourButton screen="dash" auto />
        </div>
      </header>

      <AiDay a={activity} />

      <Onboarding kids={initial.kids} guardians={initial.guardians} uploads={files.length} />

      <div className="grid grid-cols-1 gap-5 min-[821px]:grid-cols-2">
        <Panel title="선생님 차례">
          <TurnList turn={turn} />
        </Panel>
        <Panel title="기록 올리기">
          <UploadBox files={files} onUploaded={refreshAll} />
        </Panel>
      </div>

      <ProgressBoard rows={pipeline.rows} aiDown={aiDown} refresh={live.refresh} />
    </div>
  );
}

function Panel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-[22px] bg-surface p-5 shadow-[0_2px_14px_rgba(120,90,40,0.08)]">
      <h2 className="mb-3 text-[19px] font-extrabold tracking-[-0.02em]">{title}</h2>
      {children}
    </section>
  );
}

/* ── 시작하기 체크리스트 ─────────────────────────── */

const ONB_KEY = "itda.org.onboarding.closed";

function readClosed(): boolean {
  try {
    return localStorage.getItem(ONB_KEY) === "1";
  } catch {
    return false;
  }
}

/**
 * 처음 쓰는 기관: 아이가 0명이거나 올린 일지가 0건이면 대시보드 위에 세 단계를 띄운다
 * (아이 등록 → 보호자 초대 → 첫 일지 올리기). 한 번 뜨면 이 방문 동안은 남아 있고, 세 단계를 마치면 [닫기]가 생긴다.
 */
function Onboarding({ kids, guardians, uploads }: { kids: number | null; guardians: number | null; uploads: number }) {
  const revalidator = useRevalidator();
  const [closed, setClosed] = useState(readClosed);
  const [shown] = useState(() => kids === 0 || uploads === 0);
  if (closed || !shown || kids == null || guardians == null) return null;

  const steps: { title: string; text: string; done: boolean; act?: React.ReactNode }[] = [
    {
      title: "아이 등록",
      text: kids ? `${kids}명 등록했어요` : "명부에 아이를 올려요",
      done: kids > 0,
      act: (
        <Link to="/children?register=1" className="tap inline-flex items-center rounded-full bg-ink px-3.5 text-[14px] font-bold text-paper">
          아이 등록
        </Link>
      ),
    },
    {
      title: "보호자 초대",
      text: guardians ? `${guardians}명의 보호자가 공유에 동의했어요` : "초대 코드를 보호자에게 보내요",
      done: guardians > 0,
      act: (
        <Link to="/children" className="tap inline-flex items-center rounded-full border border-line2 px-3.5 text-[14px] font-bold text-ink2">
          초대 코드 보기
        </Link>
      ),
    },
    {
      title: "첫 관찰일지 올리기",
      text: uploads ? "올렸어요 · 이제 AI가 이어서 처리해요" : "파일을 올리면 AI가 나머지를 처리해요",
      done: uploads > 0,
      act: (
        <button
          type="button"
          onClick={() => document.getElementById("upload-input")?.click()}
          className="tap rounded-full bg-ink px-3.5 text-[14px] font-bold text-paper"
        >
          파일 고르기
        </button>
      ),
    },
  ];
  const done = steps.filter((x) => x.done).length;

  return (
    <section aria-labelledby="onb-t" className="rounded-[22px] bg-surface p-5 shadow-[0_2px_14px_rgba(120,90,40,0.08)]">
      <div className="mb-2 flex items-center justify-between gap-2">
        <h2 id="onb-t" className="flex items-center gap-2 text-[19px] font-extrabold tracking-[-0.02em]">
          잇다 시작하기
          <span className="rounded-full bg-surface2 px-2 py-px text-[14px] font-bold tabular-nums text-ink2">{done} / 3</span>
        </h2>
        {done === 3 ? (
          <button
            type="button"
            onClick={() => {
              try {
                localStorage.setItem(ONB_KEY, "1");
              } catch {
                // 저장이 막힌 브라우저 — 이번 방문에서만 닫는다
              }
              setClosed(true);
            }}
            className="tap rounded-full border border-line2 px-3.5 text-[14px] font-bold text-ink2 hover:bg-surface2"
          >
            닫기
          </button>
        ) : (
          <button type="button" onClick={() => revalidator.revalidate()} className="text-[13px] font-bold text-muted hover:text-ink">
            새로고침
          </button>
        )}
      </div>
      <ol className="grid grid-cols-1 gap-2.5 min-[821px]:grid-cols-3">
        {steps.map((x, i) => (
          <li key={x.title} className="flex flex-wrap items-center gap-2.5 rounded-[16px] border border-line px-3.5 py-3">
            <span
              className={`grid size-[26px] flex-none place-items-center rounded-full text-[13px] font-extrabold ${
                x.done ? "bg-accent text-white" : "bg-surface2 text-ink2"
              }`}
            >
              {x.done ? "✓" : i + 1}
            </span>
            <span className="grid min-w-0 flex-[1_1_140px]">
              <b className={x.done ? "text-ink2" : ""}>{x.title}</b>
              <span className="text-[13px] text-muted">{x.text}</span>
            </span>
            {x.done ? null : x.act}
          </li>
        ))}
      </ol>
    </section>
  );
}

/* ── 오늘 AI가 한 일 ─────────────────────────── */

function AiDay({ a }: { a: AiActivity }) {
  const items: [string, number][] = [
    ["기록 나누기", a.split],
    ["아이 매칭", a.matched],
    ["내용 검증", a.validated],
    ["일지 요약", a.summarized],
    ["주간 리포트", a.insights],
  ];
  const sum = items.reduce((n, [, v]) => n + v, 0);
  return (
    <section
      data-tour="aiday"
      aria-label="오늘 AI가 한 일"
      className="flex flex-wrap items-center gap-x-4 gap-y-2 rounded-[18px] bg-accentsoft px-5 py-3 text-[14px] text-accentink"
    >
      <span className="flex items-center gap-1.5 font-bold">
        <Sparkles aria-hidden size={16} />
        오늘 AI가 한 일 <b className="text-[16px] tabular-nums">{sum}</b>
      </span>
      {items.map(([label, n]) => (
        <span key={label} className="flex items-baseline gap-1 text-ink2">
          <b className="tabular-nums text-ink">{n}</b>
          {label}
        </span>
      ))}
    </section>
  );
}

/* ── 선생님 차례 ─────────────────────────────── */

const TURN: { key: keyof PipelineTurn; label: string; desc: string; icon: typeof Check; href: string }[] = [
  { key: "confirm", label: "아이 확인", desc: "AI가 누구의 기록인지 정하지 못한 일지", icon: UserRound, href: "/check?open=confirm" },
  { key: "blocked", label: "고쳐야 할 기록", desc: "내용 검증에서 막힌 문장", icon: PenLine, href: "/check?open=blocked" },
  { key: "review", label: "요약 검토", desc: "AI가 쓴 요약을 읽고 승인", icon: Check, href: "/gate1?open=first" },
  { key: "approve", label: "리포트 승인", desc: "주간 리포트를 읽고 보호자에게 보내기", icon: Send, href: "/children?tab=approve&open=first" },
  { key: "requests", label: "보호자 연결 요청", desc: "보호자가 기관 코드로 보낸 요청", icon: Link2, href: "/children?tab=req" },
];

function TurnList({ turn }: { turn: PipelineTurn }) {
  return (
    <ul data-tour="turn" className="grid">
      {TURN.map((t) => {
        const n = turn[t.key];
        const Icon = t.icon;
        return (
          <li
            key={t.key}
            className={`grid grid-cols-[auto_minmax(0,1fr)_auto_auto] items-center gap-3 border-t border-line py-2.5 first:border-t-0 ${n ? "" : "text-muted"}`}
          >
            <span className={`grid size-9 place-items-center rounded-full ${n ? "bg-humansoft text-human" : "bg-surface2"}`}>
              <Icon aria-hidden size={17} />
            </span>
            <span title={t.desc} className="truncate">
              <b className={n ? "text-ink" : ""}>{t.label}</b>
            </span>
            <span className={`text-[15px] font-bold tabular-nums ${n ? "text-ink" : "text-[13px] text-line2"}`}>
              {n ? `${n}건` : "없음"}
            </span>
            <span className="min-w-[96px] text-right">
              {n ? (
                <Link
                  to={t.href}
                  className="tap inline-flex items-center gap-0.5 rounded-full bg-ink px-3.5 text-[14px] font-bold text-paper hover:brightness-110"
                >
                  바로가기
                  <ChevronRight aria-hidden size={15} />
                </Link>
              ) : null}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

/* ── 기록 올리기 ─────────────────────────────── */

type UploadFailure = Extract<UploadResult, { ok: false }>;
const FAIL_TEXT = {
  invalid: `올릴 수 없는 형식이에요 (${UPLOAD_EXTENSIONS.join(" · ")})`,
  too_large: "20MB를 넘는 파일이에요",
  too_many_entries: "기록이 500건이 넘거나 한 기록이 5,000자를 넘어요. 나눠 올려 주세요",
  queue_full: "앞서 올린 기록을 처리하고 있어요. 끝나면 다시 올려 주세요",
  server_busy: "지금 올리는 파일이 많아요. 잠시 뒤 다시 올려 주세요",
  temporary: "잠시 문제가 있어 저장하지 못했어요",
} as const;

function fileStatus(f: FileProgress): { label: string; tone: "ai" | "me" | "bad" | "ok"; count: string } {
  const s = fileProgressOf(f.entries);
  const count = s.total ? ` · 기록 ${s.total}건 중 ${s.resolved}건 아이 찾음` : "";
  switch (s.tone) {
    case "splitting":
      return { label: "나누는 중", tone: "ai", count: "" };
    case "running":
      return { label: "아이 찾는 중", tone: "ai", count };
    case "waiting":
      return s.waiting
        ? { label: `아이 확인 ${s.waiting}건`, tone: "me", count }
        : { label: `고쳐야 할 기록 ${s.blocked}건`, tone: "me", count };
    case "failed":
      return { label: "실패", tone: "bad", count };
    default:
      return { label: "완료", tone: "ok", count: s.total ? ` · 기록 ${s.total}건` : "" };
  }
}

const FS_TONE = {
  ai: "bg-accentsoft text-accentink",
  me: "bg-humansoft text-human",
  bad: "bg-blocksoft text-block",
  ok: "bg-passsoft text-pass",
} as const;

function uploadedAt(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function UploadBox({ files, onUploaded }: { files: FileProgress[]; onUploaded: () => Promise<void> }) {
  const input = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [failed, setFailed] = useState<UploadFailure[]>([]);
  // 고른 뒤 확인 창에서 [올리기] 를 기다리는 파일 (#489). 올린 기록은 취소 · 삭제가 없어 한 번 묻는다
  const [pending, setPending] = useState<File[] | null>(null);
  // 기관이 올린 파일 전체. files(최근 50개)보다 오래된 파일과도 이름을 맞춰 보려고 창을 열 때 받는다
  const [allUploaded, setAllUploaded] = useState<UploadedFile[]>([]);
  const fetchSeq = useRef(0);

  function pick(list: FileList | null) {
    const picked = Array.from(list ?? []);
    // 취소한 뒤 같은 파일을 다시 골라도 onChange 가 오게 바로 비운다
    if (input.current) input.current.value = "";
    if (!picked.length || uploading || pending) return;
    setPending(picked);
    // 받기 전에는 files 로만 맞춰 본다. 실패해도 창 · 업로드는 그대로다 (경고만 덜 잡힌다)
    const seq = ++fetchSeq.current;
    setAllUploaded([]);
    getUploadedFiles()
      .then((list) => {
        if (fetchSeq.current === seq) setAllUploaded(list);
      })
      .catch(() => {});
  }

  async function upload(picked: File[]) {
    if (!picked.length || uploading) return;
    setUploading(true);
    try {
      const results = await uploadRawRecords(picked);
      setFailed(results.filter((r): r is UploadFailure => !r.ok));
      await onUploaded();
    } finally {
      setUploading(false);
      if (input.current) input.current.value = "";
    }
  }

  const states = files.map(fileStatus);
  const working = states.filter((s) => s.tone === "ai").length;
  const bad = states.filter((s) => s.tone === "bad").length;

  return (
    <div className="grid gap-3">
      <div
        data-tour="upload"
        onDragOver={(e) => {
          e.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragging(false);
          pick(e.dataTransfer.files);
        }}
        className={`flex flex-wrap items-center gap-3 rounded-[18px] border-2 border-dashed px-4 py-4 ${
          dragging ? "border-accent bg-accentsoft" : "border-line2 bg-paper"
        }`}
      >
        <span className="grid size-11 flex-none place-items-center rounded-full bg-accentsoft text-accentink">
          <Upload aria-hidden size={22} />
        </span>
        <span className="grid min-w-0 flex-1">
          <b className="text-[15px]">관찰일지 파일을 끌어다 놓으세요</b>
          <span className="text-[13px] text-muted">
            {UPLOAD_EXTENSIONS.map((e) => `.${e}`).join(" · ")} · 20MB까지 · 여러 개를 한 번에 올릴 수 있어요
          </span>
          {/* 스캔 pdf 는 올라가도 기록이 안 생긴다(BE 가 글자를 못 뽑음) — 올리기 전에 알려 둔다 */}
          <span className="text-[13px] text-muted">스캔하거나 사진으로 만든 PDF는 글자를 읽지 못해요. 워드·한글에서 PDF로 저장한 파일을 올려 주세요</span>
        </span>
        <button
          type="button"
          disabled={uploading}
          onClick={() => input.current?.click()}
          className="tap rounded-full bg-ink px-4 text-[14px] font-bold text-paper hover:brightness-110 disabled:opacity-60"
        >
          {uploading ? <Busy>올리는 중…</Busy> : "파일 고르기"}
        </button>
        <input
          ref={input}
          type="file"
          multiple
          hidden
          id="upload-input"
          accept={UPLOAD_EXTENSIONS.map((e) => `.${e}`).join(",")}
          onChange={(e) => pick(e.target.files)}
        />
      </div>

      {pending ? (
        <UploadConfirm
          files={pending}
          uploaded={[...files, ...allUploaded]}
          onCancel={() => setPending(null)}
          onConfirm={() => {
            setPending(null);
            void upload(pending);
          }}
        />
      ) : null}

      {failed.length ? (
        <div className="flex items-start gap-2 rounded-[14px] bg-blocksoft px-3.5 py-2.5 text-[14px] text-block">
          <ul className="flex-1">
            {failed.map((f, i) => (
              <li key={i}>
                <b>{f.file.name}</b> · {FAIL_TEXT[f.reason]}
              </li>
            ))}
          </ul>
          <button type="button" aria-label="닫기" onClick={() => setFailed([])} className="p-0.5">
            <X aria-hidden size={16} />
          </button>
        </div>
      ) : null}

      <div className="flex items-center gap-2 text-[14px] text-ink2">
        <span>올린 파일 {files.length}개</span>
        {working ? <span className={`rounded-full px-2 py-px text-[13px] font-bold ${FS_TONE.ai}`}>처리 중 {working}</span> : null}
        {bad ? <span className={`rounded-full px-2 py-px text-[13px] font-bold ${FS_TONE.bad}`}>실패 {bad}</span> : null}
      </div>
      <ul
        tabIndex={0}
        aria-label={`올린 파일 ${files.length}개`}
        className="flex h-[252px] flex-col overflow-y-auto overscroll-contain rounded-[14px] border border-line"
      >
        {files.length === 0 ? (
          <li className="m-auto px-4 text-[14px] text-muted">아직 올린 파일이 없어요</li>
        ) : (
          files.map((f, i) => (
            <li key={f.rawRecordId} className="flex flex-none items-center gap-3 border-t border-line px-3.5 py-2.5 first:border-t-0">
              <FileText aria-hidden size={17} className="flex-none text-muted" />
              <span className="grid min-w-0 flex-1">
                <b className="truncate text-[14px]" title={f.fileName}>
                  {f.fileName}
                </b>
                <span className="truncate text-[13px] text-muted">
                  {uploadedAt(f.uploadedAt)}
                  {states[i].count}
                </span>
              </span>
              <span className={`flex-none rounded-full px-2.5 py-0.5 text-[13px] font-bold ${FS_TONE[states[i].tone]}`}>
                {states[i].label}
              </span>
            </li>
          ))
        )}
      </ul>
    </div>
  );
}

/** 파일 이름을 맞춰 볼 때 쓰는 키. macOS 는 한글 파일 이름을 자모로 풀어(NFD) 넘길 때가 있어 NFC 로 모은다 */
const nameKey = (name: string) => name.normalize("NFC");

/**
 * 올리기 전 확인 창 (#489). 이미 올린 파일이나 함께 고른 파일과 이름이 같으면 그 줄을 빨갛게 알린다.
 * 막지는 않는다 — 고친 파일 · 이어 쓰는 파일은 같은 이름으로 다시 올리는 게 정상일 수 있다.
 * 이름만 보므로 내용이 같고 이름이 다른 파일은 못 잡는다.
 */
function UploadConfirm({
  files,
  uploaded,
  onCancel,
  onConfirm,
}: {
  files: File[];
  uploaded: UploadedFile[];
  onCancel: () => void;
  onConfirm: () => void;
}) {
  // 이름마다 가장 최근에 올린 시각. 서버 시각 문자열은 형식이 같아 글자 비교로 순서가 맞다
  const lastUploaded = new Map<string, string>();
  for (const f of uploaded) {
    const key = nameKey(f.fileName);
    const prev = lastUploaded.get(key);
    if (!prev || prev < f.uploadedAt) lastUploaded.set(key, f.uploadedAt);
  }
  const pickedCount = new Map<string, number>();
  for (const f of files) pickedCount.set(nameKey(f.name), (pickedCount.get(nameKey(f.name)) ?? 0) + 1);

  const rows = files.map((file) => {
    const key = nameKey(file.name);
    const at = lastUploaded.get(key);
    const note = at
      ? `${uploadedAt(at)}에 같은 이름 파일을 올렸어요`
      : (pickedCount.get(key) ?? 0) > 1
        ? "같이 고른 파일 중에 이름이 같은 게 있어요"
        : null;
    return { file, note };
  });
  const same = rows.filter((r) => r.note).length;

  return (
    <ConfirmDialog
      title={`파일 ${files.length}개를 올릴까요?`}
      warning="올린 뒤에는 취소할 수 없어요"
      confirmLabel="올리기"
      onCancel={onCancel}
      onConfirm={onConfirm}
    >
      {same ? (
        <p className="mb-2 flex items-start gap-1.5 font-bold text-block">
          <TriangleAlert aria-hidden size={18} className="mt-1 shrink-0" />
          같은 이름 파일이 {same}개 있어요. 같은 파일이면 취소해 주세요
        </p>
      ) : null}
      <ul className="grid max-h-[240px] gap-1.5 overflow-y-auto">
        {rows.map(({ file, note }, i) => (
          <li key={i} className={`grid rounded px-3 py-2 ${note ? "bg-blocksoft text-block" : "bg-surface2"}`}>
            <b className="truncate text-[14px]" title={file.name}>
              {file.name}
            </b>
            {note ? <span className="text-[13px] font-semibold">{note}</span> : null}
          </li>
        ))}
      </ul>
    </ConfirmDialog>
  );
}

/* ── 진행 현황 ─────────────────────────────── */

type Filter = "all" | "me" | "run" | "done";
const isMe = (r: PipelineRow) => r.who === "me";
const isRun = (r: PipelineRow) => r.who === "ai" || r.who === "wait" || r.who === "other";
const isDone = (r: PipelineRow) => r.who === "done";

function ProgressBoard({ rows, aiDown, refresh }: { rows: PipelineRow[]; aiDown: boolean; refresh: () => Promise<void> }) {
  // AI 줄의 지금 단계 (O-83). 1초마다 바뀌므로 이 보드 안에 둔다 — 대시보드 전체가 다시 그려지지 않게. 못 받으면 줄마다 null → 기존 문구
  const progressOf = useAiProgress(rows, aiDown, refresh);
  const [stage, setStage] = useState<number | null>(null);
  const [filter, setFilter] = useState<Filter>("all");
  const [showDone, setShowDone] = useState(false);

  const by = STEPS.map((_, i) => {
    const at = rows.filter((r) => r.step === i);
    return { me: at.filter(isMe).length, ai: at.filter((r) => r.who === "ai").length, wait: at.filter((r) => r.who === "wait" || r.who === "other").length };
  });
  const inStage = rows.filter((r) => stage === null || r.step === stage);
  const tabs: [Filter, string, number][] = [
    ["all", "전체", inStage.length],
    ["me", "내 차례", inStage.filter(isMe).length],
    ["run", "진행 중", inStage.filter(isRun).length],
    ["done", "완료", inStage.filter(isDone).length],
  ];
  const list =
    filter === "me" ? inStage.filter(isMe) : filter === "run" ? inStage.filter(isRun) : filter === "done" ? inStage.filter(isDone) : inStage.filter((r) => !isDone(r));
  const doneTail = filter === "all" ? inStage.filter(isDone) : [];

  return (
    <section aria-labelledby="board-t" className="rounded-[22px] bg-surface p-5 shadow-[0_2px_14px_rgba(120,90,40,0.08)]">
      <h2 id="board-t" className="mb-3 text-[19px] font-extrabold tracking-[-0.02em]">
        진행 현황
      </h2>

      <div data-tour="pipe" role="group" aria-label="단계 고르기" className="mb-3 grid grid-cols-3 gap-1.5 sm:grid-cols-6">
        {STEPS.map((name, i) => {
          const c = by[i];
          const on = stage === i;
          const Icon = STEP_ICONS[i];
          // AI 가 일하는 단계는 아이콘에 펄스 링 (점검 중이면 멈춤이라 끈다)
          const run = c.ai > 0 && !aiDown;
          return (
            <button
              key={name}
              type="button"
              title={STEP_DESC[i]}
              aria-pressed={on}
              onClick={() => setStage(on ? null : i)}
              className={`grid min-w-0 content-start gap-1.5 rounded-[14px] border px-3 py-2.5 text-left ${
                on ? "border-ink bg-ink text-paper" : c.me ? "border-mark bg-surface" : "border-line bg-surface hover:bg-surface2"
              }`}
            >
              <span className="flex min-w-0 items-center gap-1.5">
                <span className={`grid size-6 flex-none place-items-center rounded-full text-[12px] font-bold ${on ? "bg-paper text-ink" : "bg-surface2 text-ink2"}`}>
                  {i + 1}
                </span>
                <b className="truncate text-[13.5px]">{name}</b>
                <span
                  className={`relative ml-auto grid size-[22px] flex-none place-items-center rounded-full ${
                    run
                      ? "bg-accentsoft text-accentink after:absolute after:inset-0 after:animate-ring after:rounded-full after:border-2 after:border-accent motion-reduce:after:animate-none"
                      : on
                        ? "text-paper"
                        : "text-ink2"
                  }`}
                >
                  <Icon aria-hidden size={15} />
                </span>
              </span>
              <span className="flex flex-wrap gap-1 text-[11px] leading-[18px] font-bold whitespace-nowrap">
                {c.me ? <span className="rounded-full bg-mark px-1.5 text-markink">내 차례 {c.me}</span> : null}
                {c.ai ? (
                  aiDown ? (
                    <span className="rounded-full bg-surface2 px-1.5 text-ink2">멈춤 {c.ai}</span>
                  ) : (
                    <span className="rounded-full bg-accentsoft px-1.5 text-accentink">진행 중 {c.ai}</span>
                  )
                ) : null}
                {c.wait ? <span className="rounded-full bg-surface2 px-1.5 text-ink2">대기 {c.wait}</span> : null}
                {!c.me && !c.ai && !c.wait ? <span className={on ? "text-paper/60" : "text-line2"}>없음</span> : null}
              </span>
            </button>
          );
        })}
      </div>

      {/* 툴팁(title)만으로는 처음 쓰는 사람이 설명을 못 본다. 단계를 고르면 그 단계가 하는 일을 칸 아래에 한 줄로 보인다 */}
      {stage !== null ? (
        <p role="status" className="mb-3 flex items-start gap-1.5 rounded-[12px] border border-line px-3 py-2.5 text-[14px] leading-normal text-ink2">
          <Info aria-hidden size={15} className="mt-[3px] flex-none" />
          <span>
            <b className="mr-1 text-ink">{STEPS[stage]}</b>
            {STEP_DESC[stage]}
          </span>
        </p>
      ) : null}

      <div className="mb-2 flex flex-wrap items-center gap-2">
        <div role="tablist" aria-label="상태로 보기" className="flex rounded-full bg-surface2 p-1">
          {tabs.map(([key, label, n]) => (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={filter === key}
              onClick={() => setFilter(key)}
              className={`rounded-full px-3 py-1 text-[14px] font-bold ${filter === key ? "bg-surface text-ink shadow-sm" : "text-ink2"}`}
            >
              {label} <span className="tabular-nums text-muted">{n}</span>
            </button>
          ))}
        </div>
        {stage !== null ? (
          <span className="inline-flex items-center gap-1 rounded-full bg-ink py-1 pr-1 pl-3 text-[13px] font-bold text-paper">
            {STEPS[stage]} 단계
            <button type="button" aria-label={`${STEPS[stage]} 단계 보기 해제`} onClick={() => setStage(null)} className="grid size-6 place-items-center rounded-full hover:bg-paper/20">
              <X aria-hidden size={14} />
            </button>
          </span>
        ) : null}
      </div>

      <div data-tour="table" tabIndex={0} aria-label="진행 현황 목록" className="flex h-[470px] flex-col overflow-y-auto overscroll-contain rounded-[14px] border border-line max-[720px]:h-[70vh]">
        {rows.length === 0 ? (
          <p className="m-auto px-4 text-center text-[14px] text-muted">아직 올린 기록이 없어요. 관찰일지를 올리면 여기서 단계별로 볼 수 있어요</p>
        ) : list.length === 0 && !doneTail.length ? (
          <p className="m-auto text-[14px] text-muted">해당하는 기록이 없어요</p>
        ) : null}
        {list.length === 0 && doneTail.length ? <p className="px-4 py-6 text-center text-[14px] text-muted">지금 진행 중인 기록이 없어요</p> : null}
        {list.map((r, i) => (
          <JRow key={rowKey(r, i)} r={r} aiDown={aiDown} phase={progressOf(r)} />
        ))}
        {doneTail.length ? (
          <>
            <button
              type="button"
              aria-expanded={showDone}
              onClick={() => setShowDone((v) => !v)}
              className="w-full flex-none border-t border-line bg-surface2 px-4 py-2.5 text-left text-[14px] font-bold text-ink2 hover:text-ink"
            >
              {showDone ? `완료 ${doneTail.length}건 접기` : `완료 ${doneTail.length}건 펼치기`}
            </button>
            {showDone ? doneTail.map((r, i) => <JRow key={rowKey(r, i)} r={r} aiDown={aiDown} phase={null} />) : null}
          </>
        ) : null}
      </div>
    </section>
  );
}

function rowKey(r: PipelineRow, i: number): string {
  return `${r.kind}-${r.childId ?? "x"}-${r.date ?? r.week?.from}-${r.status}-${r.target?.id ?? i}`;
}

/** 서버 상태를 한 줄 문구와 바로가기로. 바로가기는 선생님 차례(target 있음)에만 */
function describe(r: PipelineRow): { text: string; hold?: boolean; skip?: boolean; go?: [string, string] } {
  const id = r.target?.id ? `&id=${encodeURIComponent(r.target.id)}` : "";
  const d = r.detail ?? {};
  switch (r.status) {
    case "matching":
      return { text: `아이를 찾는 중이에요${d.count ? ` · 그날 일지 ${d.count}건` : ""}` };
    case "match_review":
      return { text: "AI가 아이를 정하지 못했어요", go: ["아이 고르기", `/check?open=confirm${id}`] };
    case "validating":
      return { text: "AI가 기록을 확인하는 중이에요" };
    case "blocked":
      return {
        text: `막힘 · ${(d.kinds ?? []).join("·")}${d.reasons?.length ? ` — ${d.reasons.join(" / ")}` : ""}`,
        go: ["고치기", `/check?open=blocked${id}`],
      };
    case "system_error":
      return { text: "처리 중 멈췄어요 · 관리자가 확인하고 있어요" };
    case "waiting_same_date":
      return { text: "같은 날 기록이 모두 확인되면 요약해요" };
    case "summarizing":
      return { text: "AI가 요약하는 중이에요" };
    case "summary_failed":
      return {
        text: "요약에 실패했어요 · 원문을 보고 정해 주세요",
        go: ["확인하기", `/gate1?open=failed&child=${r.childId ?? ""}&date=${r.date ?? ""}`],
      };
    case "review":
      return {
        text: d.noContent ? "특이사항이 없대요 · 원문을 보고 확인해 주세요" : "요약이 왔어요 · 읽고 승인해 주세요",
        go: ["검토하기", `/gate1?open=summary${id}`],
      };
    case "review_held":
      return { text: "보류해 뒀어요", hold: true, go: ["다시 검토", `/gate1?tab=hold&open=summary${id}`] };
    case "regenerating":
      return { text: "기록이 더 들어와서 요약을 다시 만드는 중이에요" };
    case "waiting_week_end":
      return { text: r.week ? `이번 주가 끝나는 ${md(nextDay(r.week.to))}부터 리포트를 만들어요` : "이번 주가 끝나면 리포트를 만들어요" };
    case "queued":
      return { text: "곧 리포트를 만들기 시작해요" };
    case "running":
      return { text: "AI가 되풀이되는 행동을 찾는 중이에요" };
    case "retrying":
      return { text: "리포트 만들기를 다시 시도하는 중이에요" };
    case "not_consented":
      return { text: "보호자가 주간 리포트 공유를 허락하지 않아 우리 기록은 쓰지 않아요" };
    case "nothing_new":
      return { text: "이번 주는 새로 알릴 내용이 없어요", skip: true };
    case "review_here":
      return { text: "리포트가 왔어요 · 읽고 승인하면 보호자에게 가요", go: ["읽고 승인", `/children?tab=approve&open=insight${id}`] };
    case "insight_held":
      // Gate 2 보류는 되돌릴 수 없다 — 다시 읽을 화면이 없어 바로가기를 두지 않는다
      return { text: "리포트를 보류해 뒀어요", hold: true };
    case "review_elsewhere":
      return { text: `${d.approverName ?? "다른 기관"}에서 승인할 차례예요` };
    case "approved":
      return { text: "승인했어요 · 보호자가 볼 수 있어요" };
    default:
      // 서버가 새 상태를 먼저 보내도 화면이 깨지지 않게
      return { text: "" };
  }
}

function nextDay(d: string): string {
  const x = new Date(`${d}T00:00:00`);
  x.setDate(x.getDate() + 1);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${x.getFullYear()}-${pad(x.getMonth() + 1)}-${pad(x.getDate())}`;
}

const DOT = {
  d: "border-accent bg-accent text-white",
  me: "border-mark bg-mark text-markink",
  ai: "border-accent bg-accentsoft text-accentink",
  wait: "border-dashed border-line2 text-ink2",
  f: "border-line text-line2",
  skip: "border-dashed border-line text-line2",
} as const;

/** AI 단계 문구의 걸린 시간. 60초가 넘으면 초 대신 안내 문구 */
const SLOW_SEC = 60;

function JRow({ r, aiDown, phase: got }: { r: PipelineRow; aiDown: boolean; phase: AiPhase | null }) {
  const paused = aiDown && r.who === "ai";
  // 점검 중이면 점검 문구가 우선이다
  const phase = paused ? null : got;
  const info = paused ? { ...describe(r), text: "AI 점검 중이라 잠시 멈췄어요 · 돌아오면 이어서 해요" } : describe(r);
  const sub = r.kind === "week" ? `${r.week?.label ?? ""} 리포트` : r.date ? md(r.date) : "";
  const tag = isMe(r) ? (
    info.hold ? (
      <span className="rounded-full bg-surface2 px-2 py-px text-[12px] font-bold text-ink2">보류</span>
    ) : (
      <span className="rounded-full bg-humansoft px-2 py-px text-[12px] font-bold text-human">내 차례</span>
    )
  ) : paused ? (
    <span className="rounded-full bg-surface2 px-2 py-px text-[12px] font-bold text-ink2">멈춤</span>
  ) : r.who === "ai" ? (
    <span className="rounded-full bg-accentsoft px-2 py-px text-[12px] font-bold text-accentink">진행 중</span>
  ) : (
    <span className="rounded-full bg-surface2 px-2 py-px text-[12px] font-bold text-ink2">{isDone(r) ? "완료" : "대기"}</span>
  );

  return (
    <article className="grid flex-none grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-2 border-t border-line px-4 py-3 first:border-t-0 min-[721px]:grid-cols-[minmax(120px,180px)_minmax(0,1fr)_auto]">
      <div className="grid min-w-0">
        <b className="truncate text-[15px]">{r.childName ?? "아이 미정"}</b>
        <span className="truncate text-[13px] text-muted">{sub}</span>
      </div>
      <div className="col-span-full row-start-2 grid min-w-0 gap-1.5 min-[721px]:col-span-1 min-[721px]:row-start-auto">
        <div role="img" aria-label={`6단계 중 ${r.step + 1}단계 ${STEPS[r.step]}`} className="flex items-center">
          {STEPS.map((name, i) => {
            const c =
              i < r.step || (i === r.step && isDone(r) && !info.skip)
                ? "d"
                : i === r.step
                  ? r.who === "me"
                    ? "me"
                    : r.who === "ai" && !paused
                      ? "ai"
                      : info.skip
                        ? "skip"
                        : "wait"
                  : info.skip
                    ? "skip"
                    : "f";
            return (
              <span key={name} className="contents">
                {i ? <span className={`h-0.5 min-w-1.5 flex-1 ${i <= r.step ? "bg-accent" : "bg-line"}`} /> : null}
                <span
                  title={name}
                  className={`grid size-6 flex-none place-items-center rounded-full border-2 text-[11px] font-bold ${DOT[c]} ${
                    c === "ai" && phase
                      ? "relative after:absolute after:-inset-0.5 after:animate-ring after:rounded-full after:border-2 after:border-accent motion-reduce:after:animate-none"
                      : ""
                  }`}
                >
                  {c === "d" ? "✓" : i + 1}
                </span>
              </span>
            );
          })}
        </div>
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[14px]">
          {tag}
          <b>{STEPS[r.step]}</b>
          {phase ? (
            <>
              <span className="inline-flex min-w-0 items-center gap-1.5">
                <Sparkles aria-hidden size={15} className="flex-none animate-ai-spin text-accent motion-reduce:animate-none" />
                {/* 문구가 바뀌면 다시 붙어서 아래에서 올라온다. 반짝임은 안쪽에 둔다 — animation 이 겹치지 않게 */}
                <span key={phase.text} className="inline-block animate-rise motion-reduce:animate-none">
                  <span className="ai-shimmer">
                    {phase.text}
                    {r.status === "matching" && r.detail?.count ? ` · 그날 일지 ${r.detail.count}건` : ""}
                  </span>
                </span>
              </span>
              <span className="text-[13px] text-muted tabular-nums">
                · {phase.sec > SLOW_SEC ? "조금 오래 걸리고 있어요" : `${phase.sec}초`}
              </span>
            </>
          ) : (
            <span className="text-ink2">{info.text}</span>
          )}
        </div>
      </div>
      {info.go && r.target ? (
        <Link
          to={info.go[1]}
          className={`tap row-start-1 col-start-2 inline-flex items-center gap-0.5 justify-self-end rounded-full px-3.5 text-[14px] font-bold min-[721px]:col-start-3 ${
            info.hold ? "border border-line2 text-ink2 hover:bg-surface2" : "bg-ink text-paper hover:brightness-110"
          }`}
        >
          {info.go[0]}
          <ChevronRight aria-hidden size={15} />
        </Link>
      ) : (
        <span />
      )}
    </article>
  );
}
