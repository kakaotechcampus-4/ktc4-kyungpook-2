import { useEffect } from "react";
import { Link, useRevalidator } from "react-router";
import type { StageState } from "@/components/ui";
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
 *   1030_관찰일지.csv                                   기록 4건
 *   [■■■■■■][■■■■■■][■■■■■■]
 *    기록 등록  기록 나누기  아이 찾기
 *   ◉ 아이를 확정하지 못한 기록 1건 · 확인하기
 *   ✓ 기록 3건이 아이별 카드로 넘어갔어요 · 오늘의 업무에서 보기
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

const BAR_CLASS: Record<StageState, string> = {
  done: "bg-accent",
  active: "bg-accent/40 animate-pulse",
  waiting: "bg-human",
  failed: "bg-block",
  pending: "bg-surface2",
};

const LABEL_CLASS: Record<StageState, string> = {
  done: "text-accentink",
  active: "font-bold text-accentink",
  waiting: "font-bold text-human",
  failed: "font-bold text-block",
  pending: "text-muted",
};

/** 색만으로 상태를 전하지 않는다 — 화면에는 단계 이름을, 읽기 프로그램에는 상태 낱말을 준다 */
const STATE_WORD: Record<StageState, string> = {
  done: "완료",
  active: "진행 중",
  waiting: "사람 확인 대기",
  failed: "실패",
  pending: "대기 전",
};

function StageBars({ states }: { states: StageState[] }) {
  return (
    <ol
      aria-label={FILE_STAGES.map((s, i) => `${s} ${STATE_WORD[states[i]]}`).join(", ")}
      className="grid max-w-[420px] grid-cols-3 gap-1"
    >
      {FILE_STAGES.map((stage, i) => (
        <li key={stage} aria-hidden className="flex flex-col gap-1.5">
          <span className={`h-1.5 rounded-full ${BAR_CLASS[states[i]]}`} />
          <span className={`text-[12px] ${LABEL_CLASS[states[i]]}`}>{stage}</span>
        </li>
      ))}
    </ol>
  );
}

function FileRow({ file }: { file: FileProgress }) {
  const { entries } = file;
  const early = entries.filter(isBeforeChild);
  const finding = early.filter((e) => e.state === "running").length;
  const waiting = early.filter((e) => e.state === "waiting").length;
  const failed = early.filter((e) => e.state === "failed").length;
  // 매칭 단계에서 끝난 기록은 "우리 기관 아이 아님"으로 뺀 것뿐이다 (BE EXCLUDED → done)
  const excluded = early.filter((e) => e.state === "done").length;
  // 아이가 정해진 뒤 검증 · 요약에서 멈춘 기록은 넘어간 것으로 치지 않는다 — 카드에서는 "관리자 확인 중"이다
  const later = entries.filter((e) => !isBeforeChild(e));
  const stuck = later.filter((e) => e.state === "failed").length;
  const handed = later.length - stuck;

  return (
    <li className="flex flex-col gap-2.5 py-3.5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-[15px] font-medium break-all">{file.fileName}</span>
        <span className="text-[14px] text-muted tabular-nums">
          {entries.length === 0 ? "기록을 나누는 중" : `기록 ${entries.length}건`}
        </span>
      </div>

      <StageBars states={fileStageStatesOf(entries)} />

      <div className="flex flex-col gap-1 text-[14px]">
        {finding > 0 ? <span className="text-accentink">아이 찾는 중 {finding}건</span> : null}

        {waiting > 0 ? (
          <span className="flex flex-wrap items-center gap-x-1.5 text-human">
            <span aria-hidden>◉</span>
            <b className="font-semibold">아이를 확정하지 못한 기록 {waiting}건</b>
            <Link to="/queue/matching" className="text-[13px] underline">
              확인하기
            </Link>
          </span>
        ) : null}

        {failed > 0 ? (
          <span className="flex flex-wrap items-center gap-x-1.5 text-block">
            <span aria-hidden>✕</span>
            <b className="font-semibold">아이 찾기 실패 {failed}건</b>
            <Link to="/queue/matching" className="text-[13px] underline">
              확인이 필요한 기록에서 고르기
            </Link>
          </span>
        ) : null}

        {handed > 0 ? (
          <span className="flex flex-wrap items-center gap-x-1.5 font-medium text-pass">
            <span aria-hidden>✓</span>
            {handed === entries.length ? `모든 기록(${handed}건)이` : `기록 ${handed}건이`} 아이별 카드로
            넘어갔어요
            <Link to="/dashboard" className="text-[13px] text-accentink underline">
              오늘의 업무에서 보기
            </Link>
          </span>
        ) : null}

        {stuck > 0 ? (
          <span className="flex flex-wrap items-center gap-x-1.5 text-ink2">
            <span aria-hidden>!</span>
            처리하다 멈춘 기록 {stuck}건 · 관리자가 확인해요
          </span>
        ) : null}

        {excluded > 0 ? (
          <span className="text-muted">우리 기관 아이가 아니라서 뺀 기록 {excluded}건</span>
        ) : null}
      </div>
    </li>
  );
}
