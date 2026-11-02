import { useRef, useState } from "react";
import { Link, useLoaderData, useRevalidator } from "react-router";
import { Check, ChevronRight, Upload, X } from "lucide-react";
import { FileProgressList, useProgressPolling } from "@/components/org/FileProgressList";
import { Card, Note, PageHeader, Spinner } from "@/components/ui";
import {
  getChildren,
  getFileProgress,
  UPLOAD_EXTENSIONS,
  uploadRawRecords,
} from "@/lib/api";
import { fileProgressOf } from "@/lib/pipeline";
import type { FileProgress, UploadFailReason, UploadResult } from "@/lib/types";

type UploadFailure = Extract<UploadResult, { ok: false }>;

/** "csv · txt" */
const EXTENSIONS_LABEL = UPLOAD_EXTENSIONS.join(" · ");

const FAIL_MESSAGE: Record<UploadFailReason, string> = {
  invalid: `올릴 수 없는 형식입니다 (${EXTENSIONS_LABEL})`,
  too_large: "20MB를 넘는 파일입니다",
  temporary: "일시적인 오류로 저장하지 못했습니다",
};

/** 같은 파일을 두 번 고르거나 끌어다 놓아도 한 번만 올린다 */
const fileKey = (f: File) => `${f.name}:${f.size}:${f.lastModified}`;

/**
 * 진행 막대 세 칸(FILE_STAGES)이 무슨 일인지 — 멘토 리뷰(10/31) "기록 나누기 / 매칭이 무슨 과정인지 도움말".
 * 처음 쓰는 사람만 필요해서 접어 둔다. 순서 · 이름은 막대와 같다.
 *
 * 기록 나누기는 BE JournalEntrySplitter 의 규칙이다 — 줄 맨 앞의 날짜(`9/15`, `2026-09-15`)마다 새 기록.
 * 날짜 줄이 없으면 파일 전체가 기록 하나다. 규칙이 바뀌면 여기도 고친다.
 */
function StageHelp() {
  return (
    <details className="group mb-4 rounded border border-line bg-paper/60">
      <summary className="tap flex cursor-pointer list-none items-center gap-2 px-3.5 text-[14px] text-ink2 [&::-webkit-details-marker]:hidden">
        <ChevronRight
          aria-hidden
          size={15}
          strokeWidth={2}
          className="transition-transform group-open:rotate-90"
        />
        <b className="font-bold">기록 등록 · 기록 나누기 · 매칭은 무슨 과정인가요?</b>
      </summary>
      <ol className="flex flex-col gap-3 px-3.5 pb-3.5 pl-9 text-[14px] leading-6 text-ink2">
        <li>
          <b className="font-semibold text-ink">1. 기록 등록</b> — 올린 파일을 그대로 받아 둬요. 원본은 바뀌지
          않아요.
        </li>
        <li>
          <b className="font-semibold text-ink">2. 기록 나누기</b> — 파일 한 개를 날짜별 기록으로 나눠요. 줄 맨
          앞에 <code className="rounded bg-surface2 px-1">9/15</code>나{" "}
          <code className="rounded bg-surface2 px-1">2026-09-15</code>처럼 날짜가 나오면 거기서 새 기록이
          시작돼요. 날짜가 없는 파일은 통째로 기록 하나가 돼요.
        </li>
        <li>
          <b className="font-semibold text-ink">3. 매칭</b> — 나눈 기록마다 AI가 내용 속 이름을 아동 명부와
          맞춰 어느 아이의 기록인지 정해요. 여러 아이가 함께 나오거나, 명부에 없는 이름이거나, 단서가 없으면{" "}
          <Link to="/queue/matching" className="text-accentink underline">
            매칭 확인
          </Link>
          으로 보내 선생님이 직접 골라요.
        </li>
        <li className="text-muted">
          아이가 정해진 기록은 오늘의 업무의 아이별 카드로 넘어가 검증 · 요약이 이어져요.
        </li>
      </ol>
    </details>
  );
}

/** 올리지 못한 파일 — 업로드 칸 안에도, "다음에 할 일" 카드 위에도 같은 모양으로 쓴다 */
function FailureNotice({
  outcome,
  retryable,
  uploading,
  onRetry,
  onClose,
}: {
  outcome: { total: number; failed: UploadFailure[] };
  retryable: UploadFailure[];
  uploading: boolean;
  onRetry: () => void;
  onClose: () => void;
}) {
  return (
    <div className="flex flex-col gap-3 rounded border border-block/40 bg-blocksoft px-4 py-3">
      <p className="text-[15px] font-semibold">
        {outcome.failed.length === outcome.total
          ? `${outcome.total}개 모두 올리지 못했습니다`
          : `${outcome.total}개 중 ${outcome.total - outcome.failed.length}개를 올렸습니다. ${outcome.failed.length}개는 올리지 못했습니다`}
      </p>
      <ul className="flex flex-col gap-2">
        {outcome.failed.map((f, i) => (
          <li key={`${f.file.name}-${i}`} className="flex flex-col gap-0.5">
            <span className="text-[15px] font-medium break-all">
              <span aria-hidden className="text-block">
                ✕
              </span>{" "}
              {f.file.name}
            </span>
            <span className="text-[14px] text-muted">{FAIL_MESSAGE[f.reason]}</span>
          </li>
        ))}
      </ul>
      <div className="flex flex-wrap items-center gap-3">
        {retryable.length > 0 ? (
          <button
            type="button"
            onClick={onRetry}
            disabled={uploading}
            aria-busy={uploading}
            className="tap inline-flex items-center gap-2 rounded bg-accent px-4 text-[15px] font-semibold text-white hover:bg-accentink disabled:cursor-not-allowed disabled:bg-accent/75"
          >
            {uploading ? <Spinner size={16} /> : null}
            {uploading ? "올리는 중…" : `실패한 파일만 다시 올리기 (${retryable.length}개)`}
          </button>
        ) : null}
        <button
          type="button"
          onClick={onClose}
          className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2"
        >
          닫기
        </button>
      </div>
    </div>
  );
}

/**
 * 다 올린 뒤 "다음에 할 일" — 멘토 리뷰(10/31) "기록 등록 후 무엇을 해야 할지 이어 주는 넥스트 스텝".
 * 시안: https://claude.ai/artifact/DcJUa9y14gKNddcYTi5YC2
 *
 * 방금 올린 파일의 진행(폴링으로 계속 바뀐다)에서 센다. 사람이 고를 기록이 있으면 그게 "지금 할 일"이다.
 * 아래 "다음 순서"의 단계 이름은 사이드바 · 진행 막대와 같다.
 */
function UploadDone({ count, files, onMore }: { count: number; files: FileProgress[]; onMore: () => void }) {
  const summaries = files.map((f) => fileProgressOf(f.entries));
  // 서버 목록에 아직 안 들어온 파일은 나누는 중으로 본다
  const splitting = files.length < count || summaries.some((s) => s.tone === "splitting");
  const total = summaries.reduce((n, s) => n + s.total, 0);
  const toPick = summaries.reduce((n, s) => n + s.waiting + s.failed, 0);
  const running = splitting || summaries.some((s) => s.running > 0);

  const lede = splitting
    ? "파일을 날짜별 기록으로 나누고 있어요."
    : running
      ? `기록 ${total}건으로 나눴고, 지금 아이별로 정리하고 있어요.`
      : `기록 ${total}건으로 나눠 아이별 정리를 마쳤어요.`;

  return (
    <section aria-labelledby="upload-done-title" className="overflow-hidden rounded border border-line bg-surface">
      <div className="flex items-center gap-3 px-6 pt-5 pb-4">
        <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-accent text-white">
          <Check aria-hidden size={16} strokeWidth={2.6} />
        </span>
        <div className="flex flex-col gap-0.5">
          <h2 id="upload-done-title" className="text-[17px] font-bold">
            파일 {count}개를 올렸어요
          </h2>
          <p className="text-[14px] text-ink2">{lede}</p>
        </div>
      </div>

      {toPick > 0 ? (
        <div className="mx-6 mb-4 flex flex-wrap items-center justify-between gap-x-5 gap-y-2.5 rounded-md bg-humansoft px-4 py-3">
          <div className="flex flex-col">
            <span className="text-[12px] font-bold tracking-wide text-human">지금 할 일</span>
            <span className="text-[15px] font-bold">어느 아이의 기록인지 골라 주세요 · {toPick}건</span>
            <span className="text-[14px] text-ink2">AI가 확신하지 못한 기록이에요.</span>
          </div>
          <Link
            to="/queue/matching"
            className="tap inline-flex items-center rounded bg-human px-4 text-[15px] font-bold text-white hover:opacity-90"
          >
            매칭 확인하기
          </Link>
        </div>
      ) : null}

      <div className="flex flex-col gap-3.5 border-t border-surface2 bg-paper/60 px-6 pt-4 pb-5">
        <span className="text-[14px] font-bold text-ink2">다음 순서</span>
        <ol className="grid grid-cols-[repeat(auto-fit,minmax(170px,1fr))] gap-3">
          <NextStep
            n={1}
            title="나누기 · 매칭"
            caption={running ? "자동으로 진행 중" : "끝났어요"}
            tone={running ? "active" : "done"}
          />
          <NextStep
            n={2}
            title="매칭 확인"
            caption={toPick > 0 ? `지금 ${toPick}건 · 위에서 골라 주세요` : running ? "헷갈린 기록이 생길 때만" : "확인할 기록이 없어요"}
            tone={toPick > 0 ? "human" : running ? "pending" : "done"}
          />
          <NextStep n={3} title="요약" caption="오늘의 업무에서 [요약]" tone="pending" />
          <NextStep n={4} title="요약 검토" caption="문장마다 읽고 승인" tone="pending" />
        </ol>
        <div className="flex flex-wrap gap-2 pt-1.5">
          <Link
            to="/dashboard"
            className="tap inline-flex items-center rounded bg-accent px-5 text-[15px] font-semibold text-white hover:bg-accentink"
          >
            오늘의 업무로 가기 →
          </Link>
          <button
            type="button"
            onClick={onMore}
            className="tap rounded border border-line2 bg-surface px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2"
          >
            파일 더 올리기
          </button>
        </div>
      </div>
    </section>
  );
}

const STEP_BAR = { active: "border-accent", human: "border-human", done: "border-pass", pending: "border-line" };
const STEP_TITLE = { active: "text-accentink", human: "text-human", done: "text-pass", pending: "text-ink2" };

function NextStep({
  n,
  title,
  caption,
  tone,
}: {
  n: number;
  title: string;
  caption: string;
  tone: keyof typeof STEP_BAR;
}) {
  return (
    <li className={`flex flex-col gap-1 border-t-4 pt-2.5 ${STEP_BAR[tone]}`}>
      <span className={`flex items-center gap-1.5 text-[15px] font-bold ${STEP_TITLE[tone]}`}>
        {tone === "active" ? <Spinner size={15} /> : null}
        {tone === "done" ? <Check aria-hidden size={15} strokeWidth={2.6} /> : null}
        {n} · {title}
      </span>
      <span className="text-[14px] text-ink2">{caption}</span>
    </li>
  );
}

export async function clientLoader() {
  const [children, files] = await Promise.all([getChildren(), getFileProgress()]);
  return {
    pending: children.filter((c) => c.consent === "not_granted"),
    files,
  };
}

/**
 * 업로드는 즉시 응답하고 기록 나누기 · 매칭은 백그라운드에서 돈다. 그래서 등록 버튼은 올리기만 하고 끝난다.
 * 이 화면은 파일별로 "잘 들어갔나, 아이가 정해졌나" 까지 보여주고, 그 뒤(검증 · 요약 · 요약 검토)는
 * 오늘의 업무의 아이별 카드(O-34)로 넘긴다. [요약]도 거기서 누른다.
 */
export default function UploadPage() {
  const { pending, files } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();
  const inputRef = useRef<HTMLInputElement>(null);
  const [chosen, setChosen] = useState<File[]>([]);
  const [dragging, setDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  /** 지금 올리고 있는 파일 수 — 올리는 동안 놓는 곳에 보여 준다 */
  const [uploadingCount, setUploadingCount] = useState(0);
  /** 마지막으로 올린 묶음에서 실패한 파일. 전부 성공했으면 null */
  const [outcome, setOutcome] = useState<{ total: number; failed: UploadFailure[] } | null>(null);
  /**
   * 마지막으로 올린 묶음에서 잘 올라간 파일(rawRecordId). 있으면 업로드 칸 대신 "다음에 할 일" 카드를 보여 준다.
   * [파일 더 올리기]를 누르면 비운다. 실패한 것만 다시 올려 성공하면 같은 묶음에 더한다.
   */
  const [uploaded, setUploaded] = useState<string[] | null>(null);

  useProgressPolling(files);

  // 형식 · 용량 문제는 같은 파일을 다시 올려도 또 실패하므로 다시 올리기에서 뺀다
  const retryable = outcome?.failed.filter((f) => f.reason === "temporary") ?? [];

  function choose(list: FileList | null) {
    const picked = Array.from(list ?? []);
    if (picked.length === 0) return;
    setChosen((prev) => {
      const seen = new Set(prev.map(fileKey));
      return [...prev, ...picked.filter((f) => !seen.has(fileKey(f)))];
    });
  }

  /** fromPicker 가 아니면(실패한 것 다시 올리기) 새로 골라둔 파일을 지우지 않는다 */
  async function upload(targets: File[], fromPicker: boolean) {
    if (targets.length === 0) return;
    setUploading(true);
    setUploadingCount(targets.length);
    try {
      const results = await uploadRawRecords(targets);
      const failed = results.filter((r): r is UploadFailure => !r.ok);
      const ok = results.flatMap((r) => (r.ok ? [r.progress.rawRecordId] : []));
      setOutcome(failed.length > 0 ? { total: results.length, failed } : null);
      if (ok.length > 0) setUploaded((prev) => [...(fromPicker ? [] : (prev ?? [])), ...ok]);
      if (fromPicker) setChosen([]);
      revalidator.revalidate();
    } finally {
      setUploading(false);
    }
  }

  const failureNotice = outcome ? (
    <FailureNotice
      outcome={outcome}
      retryable={retryable}
      uploading={uploading}
      onRetry={() => upload(retryable.map((f) => f.file), false)}
      onClose={() => setOutcome(null)}
    />
  ) : null;

  return (
    <>
      <PageHeader
        title="기록 등록"
        description="올린 파일을 기록으로 나누고 아이와 매칭해요. 요약은 오늘의 업무에서 아이별로 눌러요"
      />

      {uploaded ? (
        <div className="flex flex-col gap-4">
          {failureNotice}
          <UploadDone
            count={uploaded.length}
            files={files.filter((f) => uploaded.includes(f.rawRecordId))}
            onMore={() => {
              setUploaded(null);
              setOutcome(null);
            }}
          />
        </div>
      ) : (
      <Card>
        <div className="flex flex-col gap-5">
          {/* 아이 · 기록 유형 · 기록 시각은 받지 않는다. 파일 하나에 여러 아이의 기록이 섞여 있어
              아이는 매칭이 판정하고, 시각은 파일 본문에서 뽑는다. */}
          <p className="text-[14px] text-muted">
            여러 아이의 기록이 한 파일에 섞여 있어도 됩니다. 아이는 자동으로 확인하고, 확신이
            낮으면 매칭 확인으로 보냅니다.
          </p>

          {uploading ? (
            <div
              role="status"
              className="flex min-h-[180px] flex-col items-center justify-center gap-2.5 rounded-md border-2 border-accent bg-accentsoft px-6 py-8 text-center"
            >
              <Spinner size={34} className="text-accent" />
              <span className="text-[17px] font-bold text-accentink">
                파일 {uploadingCount}개를 올리고 있어요
              </span>
              <span className="text-[14px] text-ink2">
                보통 몇 초면 끝나요. 다 올라가면 다음에 할 일을 알려 드릴게요.
              </span>
              <span aria-hidden className="h-1 w-60 overflow-hidden rounded-full bg-surface">
                <span className="block h-full w-2/5 animate-nav-bar rounded-full bg-accent" />
              </span>
            </div>
          ) : (
          // 영역 어디를 눌러도 파일 선택 창이 열리고, 끌어다 놓아도 된다
          <button
            type="button"
            onClick={() => inputRef.current?.click()}
            onDragEnter={(e) => {
              e.preventDefault();
              setDragging(true);
            }}
            onDragOver={(e) => {
              e.preventDefault();
              e.dataTransfer.dropEffect = "copy";
            }}
            onDragLeave={(e) => {
              // 안쪽 글자 위로 옮겨 갈 때도 leave 가 온다 — 영역을 정말 벗어났을 때만 끈다
              if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setDragging(false);
            }}
            onDrop={(e) => {
              e.preventDefault();
              setDragging(false);
              choose(e.dataTransfer.files);
            }}
            className={`flex min-h-[180px] flex-col items-center justify-center gap-2 rounded-md border-2 border-dashed px-6 py-8 text-center transition-colors ${
              dragging
                ? "border-accent bg-accentsoft"
                : "border-line2 bg-paper hover:border-accent hover:bg-accentsoft/40"
            }`}
          >
            <Upload aria-hidden size={30} strokeWidth={1.6} className="text-accent" />
            <span className="text-[17px] font-bold text-ink">
              {dragging ? "여기에 놓으세요" : "파일을 여기에 끌어다 놓거나, 눌러서 고르세요"}
            </span>
            <span className="text-[14px] text-muted">
              일지 · 특이사항 메모 ({EXTENSIONS_LABEL}) · 한 파일 20MB까지
            </span>
            <span className="text-[13px] text-muted">PDF는 곧 지원할 예정입니다</span>
          </button>
          )}
          <input
            ref={inputRef}
            type="file"
            multiple
            accept={UPLOAD_EXTENSIONS.map((ext) => `.${ext}`).join(",")}
            onChange={(e) => {
              choose(e.target.files);
              // 같은 파일을 빼고 다시 골라도 onChange 가 오도록 비운다
              e.target.value = "";
            }}
            className="sr-only"
            tabIndex={-1}
            aria-hidden
          />

          {chosen.length > 0 ? (
            <ul aria-label="올릴 파일" className="flex flex-wrap gap-2">
              {chosen.map((f) => (
                <li
                  key={fileKey(f)}
                  className="flex items-center gap-1 rounded border border-line bg-paper py-1 pr-1 pl-2.5 text-[14px]"
                >
                  <span className="break-all">{f.name}</span>
                  <button
                    type="button"
                    aria-label={`${f.name} 빼기`}
                    onClick={() => setChosen((prev) => prev.filter((x) => x !== f))}
                    disabled={uploading}
                    className="flex size-8 items-center justify-center rounded text-muted hover:bg-surface2 hover:text-ink disabled:invisible"
                  >
                    <X aria-hidden size={14} strokeWidth={2} />
                  </button>
                </li>
              ))}
            </ul>
          ) : null}

          <div className="flex flex-wrap items-center gap-3">
            <button
              type="button"
              onClick={() => upload(chosen, true)}
              disabled={chosen.length === 0 || uploading}
              aria-busy={uploading}
              className={`tap inline-flex items-center gap-2 rounded px-5 text-[16px] font-semibold disabled:cursor-not-allowed ${
                uploading
                  ? "bg-accent/75 text-white"
                  : "bg-accent text-white hover:bg-accentink disabled:bg-line2 disabled:text-muted"
              }`}
            >
              {uploading ? (
                <>
                  <Spinner size={18} />
                  올리는 중…
                </>
              ) : chosen.length > 0 ? (
                `등록하고 처리 시작 (${chosen.length}개)`
              ) : (
                "등록하고 처리 시작"
              )}
            </button>
          </div>
          {failureNotice}
        </div>
      </Card>
      )}

      <Card className="mt-5">
        <h2 className="mb-1 text-[17px] font-bold">파일별 진행 현황</h2>
        <p className="mb-4 text-[14px] text-muted">
          파일이 잘 들어갔는지, 기록마다 아이가 정해졌는지 보여줍니다. 이 화면을 떠나도 처리는
          이어집니다. 그 뒤(검증 · 요약 · 요약 검토)는{" "}
          <Link to="/dashboard" className="text-accentink underline">
            오늘의 업무
          </Link>
          에서 아이별로 이어집니다.
        </p>
        <StageHelp />
        {files.length === 0 ? (
          <p className="text-[14px] text-muted">아직 올린 파일이 없습니다.</p>
        ) : (
          <FileProgressList files={files} />
        )}
      </Card>

      {pending.length > 0 ? (
        <div className="mt-5">
          <Note>
            <b className="font-semibold">{pending.map((c) => c.name).join(", ")}</b> 은(는) 아직
            보호자 동의를 기다리는 중이라 기록을 올릴 수 없습니다. 동의가 완료되면 등록이
            열립니다.
          </Note>
        </div>
      ) : null}
    </>
  );
}
