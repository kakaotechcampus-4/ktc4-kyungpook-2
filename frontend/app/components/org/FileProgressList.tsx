import { useEffect } from "react";
import { Link, useRevalidator } from "react-router";
import { StageDots } from "@/components/ui";
import { FILE_STAGES, fileStageStatesOf, isBeforeChild } from "@/lib/pipeline";
import type { FileProgress } from "@/lib/types";

const POLL_MS = 1500;

/**
 * 아이를 찾는 중인 파일이 있는 동안만 주기적으로 다시 불러온다.
 * 아이가 정해진 뒤의 진행(검증 · 요약)은 이 화면이 보여주지 않으니 기다리지 않는다 —
 * 서버는 요약 대기도 running 으로 내려서, 그것까지 보면 폴링이 끝나지 않는다.
 */
export function useProgressPolling(files: FileProgress[]) {
  const revalidator = useRevalidator();
  const moving = files.some(
    (f) => f.entries.length === 0 || f.entries.some((e) => isBeforeChild(e) && e.state === "running"),
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
 * 파일 단위 처리 현황 — "올린 파일이 잘 들어갔나, 아이가 정해졌나".
 *
 * 파일 한 개에 여러 아이 기록이 섞여 있어, 아이가 정해진 기록은 오늘의 업무의 아이별 카드(O-34)로
 * 넘겨준다. 그 뒤 검증 · 요약 · 1차 검토는 거기서 이어진다.
 *
 *   1030_관찰일지.csv                       기록 4건
 *   ● ● ◉   아이를 확정하지 못한 기록 1건 · 확인하기 →
 *           기록 3건이 아이별 카드로 넘어갔어요 · 오늘의 업무에서 보기 →
 *
 * 실패에는 버튼을 두지 않는다. 매칭 실패는 확인 필요 큐로 가고(api-spec O-26), 재처리 API(O-27)는 없다.
 */
export function FileProgressList({ files }: { files: FileProgress[] }) {
  return (
    <ul className="flex flex-col divide-y divide-line border-y border-line">
      {files.map((f) => (
        <FileRow key={f.rawRecordId} file={f} />
      ))}
    </ul>
  );
}

function FileRow({ file }: { file: FileProgress }) {
  const { entries } = file;
  const early = entries.filter(isBeforeChild);
  const finding = early.filter((e) => e.state === "running").length;
  const waiting = early.filter((e) => e.state === "waiting").length;
  const failed = early.filter((e) => e.state === "failed").length;
  const handed = entries.length - early.length;

  return (
    <li className="flex flex-col gap-2 py-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-[15px] font-medium break-all">{file.fileName}</span>
        <span className="text-[14px] text-muted tabular-nums">
          {entries.length === 0 ? "기록을 나누는 중" : `기록 ${entries.length}건`}
        </span>
      </div>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5 text-[14px]">
        <StageDots stages={[...FILE_STAGES]} states={fileStageStatesOf(entries)} />

        {finding > 0 ? <span className="text-muted">아이 찾는 중 {finding}건</span> : null}

        {waiting > 0 ? (
          <span className="inline-flex flex-wrap items-center gap-x-1.5 text-human">
            <span aria-hidden>◉</span>
            <b className="font-semibold">아이를 확정하지 못한 기록 {waiting}건</b>
            <Link to="/queue/matching" className="text-[13px] underline">
              확인하기
            </Link>
          </span>
        ) : null}

        {failed > 0 ? (
          <span className="inline-flex flex-wrap items-center gap-x-1.5 text-block">
            <span aria-hidden>✕</span>
            <b className="font-semibold">아이 찾기 실패 {failed}건</b>
            <Link to="/queue/matching" className="text-[13px] underline">
              확인이 필요한 기록에서 고르기
            </Link>
          </span>
        ) : null}

        {handed > 0 ? (
          <span className="inline-flex flex-wrap items-center gap-x-1.5 font-medium text-pass">
            <span aria-hidden>✓</span>
            {handed === entries.length ? `모든 기록(${handed}건)이` : `기록 ${handed}건이`} 아이별 카드로
            넘어갔어요
            <Link to="/dashboard" className="text-[13px] text-accentink underline">
              오늘의 업무에서 보기
            </Link>
          </span>
        ) : null}
      </div>
    </li>
  );
}
