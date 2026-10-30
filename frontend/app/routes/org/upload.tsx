import { useRef, useState } from "react";
import { Link, useLoaderData, useRevalidator } from "react-router";
import { Upload, X } from "lucide-react";
import { FileProgressList, useProgressPolling } from "@/components/org/FileProgressList";
import { Card, Note, PageHeader } from "@/components/ui";
import {
  getChildren,
  getFileProgress,
  UPLOAD_EXTENSIONS,
  uploadRawRecords,
} from "@/lib/api";
import type { UploadFailReason, UploadResult } from "@/lib/types";

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

export async function clientLoader() {
  const [children, files] = await Promise.all([getChildren(), getFileProgress()]);
  return {
    pending: children.filter((c) => c.consent === "not_granted"),
    files,
  };
}

/**
 * 업로드는 즉시 응답하고 기록 나누기 · 매칭은 백그라운드에서 돈다. 그래서 등록 버튼은 올리기만 하고 끝난다.
 * 이 화면은 파일별로 "잘 들어갔나, 아이가 정해졌나" 까지 보여주고, 그 뒤(검증 · 요약 · 1차 검토)는
 * 오늘의 업무의 아이별 카드(O-34)로 넘긴다. [요약]도 거기서 누른다.
 */
export default function UploadPage() {
  const { pending, files } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();
  const inputRef = useRef<HTMLInputElement>(null);
  const [chosen, setChosen] = useState<File[]>([]);
  const [dragging, setDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  /** 마지막으로 올린 묶음에서 실패한 파일. 전부 성공했으면 null */
  const [outcome, setOutcome] = useState<{ total: number; failed: UploadFailure[] } | null>(null);

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
    try {
      const results = await uploadRawRecords(targets);
      const failed = results.filter((r): r is UploadFailure => !r.ok);
      setOutcome(failed.length > 0 ? { total: results.length, failed } : null);
      if (fromPicker) setChosen([]);
      revalidator.revalidate();
    } finally {
      setUploading(false);
    }
  }

  return (
    <>
      <PageHeader
        title="기록 등록"
        description="올린 파일을 기록으로 나누고 아이를 찾아요. 요약은 오늘의 업무에서 아이별로 눌러요"
      />

      <Card>
        <div className="flex flex-col gap-5">
          {/* 아이 · 기록 유형 · 기록 시각은 받지 않는다. 파일 하나에 여러 아이의 기록이 섞여 있어
              아이는 매칭이 판정하고, 시각은 파일 본문에서 뽑는다. */}
          <p className="text-[14px] text-muted">
            여러 아이의 기록이 한 파일에 섞여 있어도 됩니다. 아이는 자동으로 확인하고, 확신이
            낮으면 확인이 필요한 기록으로 보냅니다.
          </p>

          {/* 영역 어디를 눌러도 파일 선택 창이 열리고, 끌어다 놓아도 된다 */}
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
                    className="flex size-8 items-center justify-center rounded text-muted hover:bg-surface2 hover:text-ink"
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
              className="tap rounded bg-accent px-5 text-[16px] font-semibold text-white hover:bg-accentink disabled:cursor-not-allowed disabled:bg-line2 disabled:text-muted"
            >
              {uploading
                ? "올리는 중…"
                : chosen.length > 0
                  ? `등록하고 처리 시작 (${chosen.length}개)`
                  : "등록하고 처리 시작"}
            </button>
          </div>
          {outcome ? (
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
                    onClick={() => upload(retryable.map((f) => f.file), false)}
                    disabled={uploading}
                    className="tap rounded bg-accent px-4 text-[15px] font-semibold text-white hover:bg-accentink disabled:cursor-not-allowed disabled:bg-line2 disabled:text-muted"
                  >
                    실패한 파일만 다시 올리기 ({retryable.length}개)
                  </button>
                ) : null}
                <button
                  type="button"
                  onClick={() => setOutcome(null)}
                  className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2"
                >
                  닫기
                </button>
              </div>
            </div>
          ) : null}
        </div>
      </Card>

      <Card className="mt-5">
        <h2 className="mb-1 text-[17px] font-bold">파일별 진행 현황</h2>
        <p className="mb-4 text-[14px] text-muted">
          파일이 잘 들어갔는지, 기록마다 아이가 정해졌는지 보여줍니다. 이 화면을 떠나도 처리는
          이어집니다. 그 뒤(검증 · 요약 · 1차 검토)는{" "}
          <Link to="/dashboard" className="text-accentink underline">
            오늘의 업무
          </Link>
          에서 아이별로 이어집니다.
        </p>
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
