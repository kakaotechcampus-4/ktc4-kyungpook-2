import { useEffect } from "react";
import { Link, useRevalidator } from "react-router";
import { StageDots } from "@/components/ui";
import {
  GATE1_INDEX,
  MATCHING_INDEX,
  PIPELINE_STAGES,
  VALIDATION_INDEX,
  stageStatesOf,
} from "@/lib/pipeline";
import type { FileProgress } from "@/lib/types";

/** 사람이 멈춰 있는 단계마다 교사가 가야 할 곳 */
const WAITING_AT: Record<number, { label: string; href: string }> = {
  [MATCHING_INDEX]: { label: "매칭 확인", href: "/queue/matching" },
  [VALIDATION_INDEX]: { label: "수정 요청", href: "/queue/reinput" },
  [GATE1_INDEX]: { label: "1차 검토", href: "/gate1" },
};

const POLL_MS = 1500;

/**
 * 자동으로 진행 중인 파일이 있는 동안만 주기적으로 다시 불러온다.
 * 전부 사람 대기·실패·완료로 멈추면 폴링도 멈춘다 — 더 기다려도 바뀌지 않기 때문이다.
 */
export function useProgressPolling(files: FileProgress[]) {
  const revalidator = useRevalidator();
  const moving = files.some(
    (f) => f.entries.length === 0 || f.entries.some((e) => e.state === "running"),
  );
  useEffect(() => {
    if (!moving) return;
    const t = setInterval(() => {
      if (revalidator.state === "idle") revalidator.revalidate();
    }, POLL_MS);
    return () => clearInterval(t);
  }, [moving, revalidator]);
}

/**
 * 파일 단위 처리 현황.
 *
 * 업로드는 즉시 끝나고 처리는 백그라운드에서 돈다. 교사가 궁금한 건 "내가 올린 파일이
 * 어디까지 갔나" 라서 기록 건이 아니라 파일을 한 줄로 묶는다.
 *
 *   관찰일지_9월3주.docx          기록 12건
 *   ● ● ● ✕ ◉   ◉ 교사 확인 8건 · 1차 검토 8   ✕ 실패 1건 [다시 시도]
 */
export function FileProgressList({
  files,
  onRetry,
}: {
  files: FileProgress[];
  onRetry?: (rawRecordId: string) => void;
}) {
  return (
    <ul className="flex flex-col divide-y divide-line border-y border-line">
      {files.map((f) => (
        <FileRow key={f.rawRecordId} file={f} onRetry={onRetry} />
      ))}
    </ul>
  );
}

function FileRow({
  file,
  onRetry,
}: {
  file: FileProgress;
  onRetry?: (rawRecordId: string) => void;
}) {
  const { entries } = file;
  const states = stageStatesOf(entries);
  const waiting = entries.filter((e) => e.state === "waiting");
  const failed = entries.filter((e) => e.state === "failed").length;
  const running = entries.filter((e) => e.state === "running").length;
  const allDone = entries.length > 0 && entries.every((e) => e.state === "done");

  // 멈춘 단계별 건수 — 어느 화면으로 가야 하는지 함께 보여준다
  const waitingByStage = Object.entries(
    waiting.reduce<Record<number, number>>((acc, e) => {
      acc[e.stageIndex] = (acc[e.stageIndex] ?? 0) + 1;
      return acc;
    }, {}),
  ).map(([stage, count]) => ({ ...WAITING_AT[Number(stage)], count }));

  return (
    <li className="flex flex-col gap-2 py-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-[15px] font-medium break-all">{file.fileName}</span>
        <span className="text-[14px] text-muted tabular-nums">
          {entries.length === 0 ? "기록을 나누는 중" : `기록 ${entries.length}건`}
        </span>
      </div>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5 text-[14px]">
        <StageDots stages={[...PIPELINE_STAGES]} states={states} />

        {waiting.length > 0 ? (
          <span className="inline-flex flex-wrap items-center gap-x-1.5 text-human">
            <span aria-hidden>◉</span>
            <b className="font-semibold">교사 확인 {waiting.length}건</b>
            {waitingByStage.map((w) =>
              w.href ? (
                <Link key={w.href} to={w.href} className="text-[13px] underline">
                  {w.label} {w.count}
                </Link>
              ) : null,
            )}
          </span>
        ) : null}

        {failed > 0 ? (
          <span className="inline-flex items-center gap-1.5 text-block">
            <span aria-hidden>✕</span>
            <b className="font-semibold">실패 {failed}건</b>
            {onRetry ? (
              <button
                type="button"
                onClick={() => onRetry(file.rawRecordId)}
                className="rounded border border-block/40 px-2 py-0.5 text-[13px] font-semibold hover:bg-blocksoft"
              >
                다시 시도
              </button>
            ) : null}
          </span>
        ) : null}

        {running > 0 ? <span className="text-muted">처리 중 {running}건</span> : null}
        {allDone ? <span className="font-medium text-pass">✓ 처리 완료</span> : null}
      </div>
    </li>
  );
}
