import { useState } from "react";
import { Link, useLoaderData, useRevalidator } from "react-router";
import { FileProgressList, useProgressPolling } from "@/components/org/FileProgressList";
import { Card, Note, PageHeader, PipelineStepper } from "@/components/ui";
import { getChildren, getFileProgress, retryFailedEntries, uploadRawRecords } from "@/lib/api";
import { PIPELINE_STAGES, stageStatesOf } from "@/lib/pipeline";
import type { UploadFailReason, UploadResult } from "@/lib/types";

type UploadFailure = Extract<UploadResult, { ok: false }>;

const FAIL_MESSAGE: Record<UploadFailReason, string> = {
  invalid: "올릴 수 없는 형식입니다 (csv · txt · pdf · jpg · png · hwp)",
  too_large: "20MB를 넘는 파일입니다",
  temporary: "일시적인 오류로 저장하지 못했습니다",
};

export async function clientLoader() {
  const [children, files] = await Promise.all([getChildren(), getFileProgress()]);
  return {
    pending: children.filter((c) => c.status === "pending_consent"),
    files,
  };
}

/**
 * 업로드는 즉시 응답하고 매칭 · 검증 · 요약은 백그라운드에서 돈다.
 * 그래서 등록 버튼은 올리기만 하고 끝난다. 이후 진행은 방금 올린 파일의 현황으로 보여준다
 * — 창을 닫았다가 대시보드에서 다시 봐도 같은 현황이다.
 */
export default function UploadPage() {
  const { pending, files } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();
  const [chosen, setChosen] = useState<File[]>([]);
  const [uploadedIds, setUploadedIds] = useState<string[]>([]);
  const [uploading, setUploading] = useState(false);
  /** 마지막으로 올린 묶음에서 실패한 파일. 전부 성공했으면 null */
  const [outcome, setOutcome] = useState<{ total: number; failed: UploadFailure[] } | null>(null);
  /** 파일 입력을 비우려면 새로 그려야 한다 */
  const [inputKey, setInputKey] = useState(0);

  const mine = files.filter((f) => uploadedIds.includes(f.rawRecordId));
  const latest = mine[0];
  useProgressPolling(mine);

  // 형식 · 용량 문제는 같은 파일을 다시 올려도 또 실패하므로 다시 올리기에서 뺀다
  const retryable = outcome?.failed.filter((f) => f.reason === "temporary") ?? [];

  /** fromPicker 가 아니면(실패한 것 다시 올리기) 새로 골라둔 파일을 지우지 않는다 */
  async function upload(targets: File[], fromPicker: boolean) {
    if (targets.length === 0) return;
    setUploading(true);
    try {
      const results = await uploadRawRecords(targets);
      const failed = results.filter((r): r is UploadFailure => !r.ok);
      const created = results.flatMap((r) => (r.ok ? [r.progress.rawRecordId] : []));
      setUploadedIds((ids) => [...created, ...ids]);
      setOutcome(failed.length > 0 ? { total: results.length, failed } : null);
      if (fromPicker) {
        setChosen([]);
        setInputKey((k) => k + 1);
      }
      revalidator.revalidate();
    } finally {
      setUploading(false);
    }
  }

  return (
    <>
      <PageHeader
        title="기록 등록"
        description="등록하면 매칭 · 검증 · 요약이 자동으로 진행되고 1차 검토 대기로 이동합니다"
      />

      <Card>
        <form className="flex flex-col gap-5">
          {/* 아이 · 기록 유형 · 기록 시각은 받지 않는다. 파일 하나에 여러 아이의 기록이 섞여 있어
              아이는 매칭이 판정하고, 시각은 파일 본문에서 뽑는다. */}
          <p className="text-[14px] text-muted">
            여러 아이의 기록이 한 파일에 섞여 있어도 됩니다. 아이는 자동으로 확인하고, 확신이
            낮으면 확인이 필요한 기록으로 보냅니다.
          </p>

          <div className="flex flex-col items-center gap-2 rounded border border-dashed border-line2 bg-paper px-6 py-10 text-center">
            <span aria-hidden className="text-2xl text-muted">
              ⬆
            </span>
            <p className="text-[16px] font-semibold text-ink2">파일을 끌어다 놓거나 선택하세요</p>
            <p className="text-[14px] text-muted">사진 · 일지 · 특이사항 메모</p>
            <input
              key={inputKey}
              type="file"
              multiple
              onChange={(e) => setChosen(Array.from(e.target.files ?? []))}
              className="mt-2 text-[14px]"
            />
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <button
              type="button"
              onClick={() => upload(chosen, true)}
              disabled={chosen.length === 0 || uploading}
              className="tap rounded bg-accent px-5 text-[16px] font-semibold text-white hover:bg-accentink disabled:cursor-not-allowed disabled:bg-line2 disabled:text-muted"
            >
              {uploading ? "올리는 중…" : "등록하고 처리 시작"}
            </button>
            {chosen.length > 0 ? (
              <span className="text-[14px] text-muted">{chosen.length}개 파일 선택됨</span>
            ) : null}
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
        </form>
      </Card>

      {latest ? (
        <Card className="mt-5">
          <h2 className="mb-1 text-[17px] font-bold">방금 올린 파일</h2>
          <p className="mb-4 text-[14px] text-muted">
            등록은 끝났습니다. 처리는 백그라운드에서 이어지니 이 화면을 떠나도 됩니다. 전체
            현황은{" "}
            <Link to="/dashboard" className="text-accentink underline">
              오늘의 업무
            </Link>
            에서 볼 수 있습니다.
          </p>
          <PipelineStepper
            stages={[...PIPELINE_STAGES]}
            states={stageStatesOf(latest.entries)}
            className="mb-5 rounded border border-line bg-surface2 p-4"
          />
          <FileProgressList
            files={mine}
            onRetry={async (id) => {
              await retryFailedEntries(id);
              revalidator.revalidate();
            }}
          />
        </Card>
      ) : null}

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
