import { useState } from "react";
import { Link, useLoaderData, useRevalidator } from "react-router";
import { FileProgressList, useProgressPolling } from "@/components/org/FileProgressList";
import { Card, Note, PageHeader, PipelineStepper } from "@/components/ui";
import { getChildren, getFileProgress, retryFailedEntries, uploadRawRecords } from "@/lib/api";
import { PIPELINE_STAGES, stageStatesOf } from "@/lib/pipeline";

export async function clientLoader() {
  const [children, files] = await Promise.all([getChildren(), getFileProgress()]);
  return {
    active: children.filter((c) => c.status === "active"),
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
  const { active, pending, files } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();
  const [chosen, setChosen] = useState<File[]>([]);
  const [uploadedIds, setUploadedIds] = useState<string[]>([]);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 파일 입력을 비우려면 새로 그려야 한다 */
  const [inputKey, setInputKey] = useState(0);

  const mine = files.filter((f) => uploadedIds.includes(f.rawRecordId));
  const latest = mine[0];
  useProgressPolling(mine);

  async function upload() {
    if (chosen.length === 0) return;
    setUploading(true);
    setError(null);
    try {
      const created = await uploadRawRecords(chosen);
      setUploadedIds((ids) => [...created.map((f) => f.rawRecordId), ...ids]);
      setChosen([]);
      setInputKey((k) => k + 1);
      revalidator.revalidate();
    } catch {
      setError("업로드하지 못했습니다. 잠시 뒤 다시 시도해주세요.");
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
          <label className="flex flex-col gap-1.5">
            <span className="text-[15px] font-semibold">아이 (선택)</span>
            <select
              defaultValue=""
              className="tap rounded border border-line2 bg-surface px-3 text-[16px] outline-none focus:border-accent"
            >
              <option value="">자동 매칭에 맡기기</option>
              {active.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name} · {c.birthDate}
                </option>
              ))}
            </select>
            <span className="text-[13px] text-muted">
              비워두면 자동으로 아이를 확인하고, 확신이 낮으면 확인이 필요한 기록으로 보냅니다.
            </span>
          </label>

          <div className="grid gap-4 sm:grid-cols-2">
            <label className="flex flex-col gap-1.5">
              <span className="text-[15px] font-semibold">기록 유형</span>
              <select className="tap rounded border border-line2 bg-surface px-3 text-[16px] outline-none focus:border-accent">
                <option>관찰일지</option>
                <option>활동일지</option>
                <option>특이사항</option>
                <option>사진</option>
              </select>
            </label>
            <label className="flex flex-col gap-1.5">
              <span className="text-[15px] font-semibold">기록 시각</span>
              <input
                type="datetime-local"
                defaultValue="2026-08-21T11:40"
                className="tap rounded border border-line2 bg-surface px-3 text-[16px] outline-none focus:border-accent"
              />
            </label>
          </div>

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
              onClick={upload}
              disabled={chosen.length === 0 || uploading}
              className="tap rounded bg-accent px-5 text-[16px] font-semibold text-white hover:bg-accentink disabled:cursor-not-allowed disabled:bg-line2 disabled:text-muted"
            >
              {uploading ? "올리는 중…" : "등록하고 처리 시작"}
            </button>
            {chosen.length > 0 ? (
              <span className="text-[14px] text-muted">{chosen.length}개 파일 선택됨</span>
            ) : null}
          </div>
          {error ? <p className="text-[14px] text-block">{error}</p> : null}
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
