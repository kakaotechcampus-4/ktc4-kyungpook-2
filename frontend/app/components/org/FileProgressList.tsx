import { useEffect } from "react";
import { Link, useRevalidator } from "react-router";
import type { StageState } from "@/components/ui";
import { Spinner } from "@/components/ui";
import { FILE_STAGES, type FileTone, fileProgressOf, fileStageStatesOf, isBeforeChild } from "@/lib/pipeline";
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
 * 넘겨준다. 그 뒤 검증 · 요약 · 요약 검토는 거기서 이어진다.
 *
 *   1030_특이사항.txt  기록 2건                              83%
 *   [■■■■■■■■■■■|■■■■■■■■■■■|■■■■■▒▒▒░░░░░░]
 *    ✓ 기록 등록     ✓ 기록 나누기     매칭
 *   ◌ 매칭 중 · 2건 중 1건의 아이를 정했어요
 *
 * 막대 하나가 세 단계를 ⅓씩 나눠 가진다 (lib/pipeline.ts fileProgressOf). 시안:
 * https://claude.ai/artifact/DcJUa9y14gKNddcYTi5YC2
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

/** 막대 · 퍼센트 색. 진행 중은 빗금이 흐른다 */
const FILL_CLASS: Record<FileTone, string> = {
  splitting: "bg-accent progress-stripes",
  running: "bg-accent progress-stripes",
  waiting: "bg-human",
  failed: "bg-block",
  done: "bg-pass",
};

const TONE_TEXT: Record<FileTone, string> = {
  splitting: "text-accentink",
  running: "text-accentink",
  waiting: "text-human",
  failed: "text-block",
  done: "text-pass",
};

/** 막대 아래 단계 이름. 지난 단계는 ✓, 지금 단계는 굵게 색을 입힌다 */
const LABEL_CLASS: Record<StageState, string> = {
  done: "text-ink2",
  active: "font-bold text-accentink",
  waiting: "font-bold text-human",
  failed: "font-bold text-block",
  pending: "text-muted",
};

/** 색만으로 상태를 전하지 않는다 — 읽기 프로그램에는 상태 낱말을 준다 */
const STATE_WORD: Record<StageState, string> = {
  done: "완료",
  active: "진행 중",
  waiting: "사람 확인 대기",
  failed: "실패",
  pending: "대기 전",
};

function ProgressBar({ file }: { file: FileProgress }) {
  const { percent, tone } = fileProgressOf(file.entries);
  const states = fileStageStatesOf(file.entries);
  return (
    <div className="flex flex-col gap-1.5">
      <div
        role="progressbar"
        aria-label={`${file.fileName} 진행률`}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
        aria-valuetext={`${percent}% · ${FILE_STAGES.map((s, i) => `${s} ${STATE_WORD[states[i]]}`).join(", ")}`}
        className="relative h-3 overflow-hidden rounded-full bg-surface2"
      >
        <div
          className={`h-full rounded-full transition-[width] duration-500 ease-out ${FILL_CLASS[tone]}`}
          style={{ width: `${percent}%` }}
        />
        {/* 단계 경계 눈금 */}
        <span aria-hidden className="absolute inset-y-0 left-1/3 w-0.5 bg-surface" />
        <span aria-hidden className="absolute inset-y-0 left-2/3 w-0.5 bg-surface" />
      </div>
      <ol aria-hidden className="grid grid-cols-3 gap-1 text-[13px]">
        {FILE_STAGES.map((stage, i) => (
          <li key={stage} className={LABEL_CLASS[states[i]]}>
            {states[i] === "done" ? "✓ " : ""}
            {stage}
          </li>
        ))}
      </ol>
    </div>
  );
}

function FileRow({ file }: { file: FileProgress }) {
  const { entries } = file;
  const p = fileProgressOf(entries);
  const early = entries.filter(isBeforeChild);
  // 매칭 단계에서 끝난 기록은 "우리 기관 아이 아님"으로 뺀 것뿐이다 (BE EXCLUDED → done)
  const excluded = early.filter((e) => e.state === "done").length;
  // 아이가 정해진 뒤 검증 · 요약에서 멈춘 기록은 넘어간 것으로 치지 않는다 — 카드에서는 "관리자 확인 중"이다
  const later = entries.filter((e) => !isBeforeChild(e));
  const stuck = later.filter((e) => e.state === "failed").length;
  const handed = later.length - stuck;

  return (
    <li className="flex flex-col gap-2.5 py-4">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <span className="flex min-w-0 flex-wrap items-baseline gap-x-2.5">
          <span className="text-[16px] font-semibold break-all">{file.fileName}</span>
          <span className="text-[14px] text-muted tabular-nums">
            {p.tone === "splitting" ? "기록을 나누는 중" : `기록 ${p.total}건`}
          </span>
        </span>
        <span className={`text-[15px] font-bold tabular-nums ${TONE_TEXT[p.tone]}`}>{p.percent}%</span>
      </div>

      <ProgressBar file={file} />

      <div className="flex flex-col gap-1 text-[15px]">
        {p.tone === "splitting" ? (
          <span className="flex items-center gap-1.5 text-accentink">
            <Spinner size={15} />
            <b className="font-semibold">기록을 나누는 중</b>
          </span>
        ) : null}

        {p.failed > 0 ? (
          <span className="flex flex-wrap items-center gap-x-1.5 text-block">
            <span aria-hidden>✕</span>
            <b className="font-semibold">매칭 실패</b>· {p.failed}건은 AI 판정을 받지 못했어요 ·
            <Link to="/queue/matching" className="font-semibold underline">
              매칭 확인에서 고르기
            </Link>
          </span>
        ) : null}

        {p.waiting > 0 ? (
          <span className="flex flex-wrap items-center gap-x-1.5 text-human">
            <span aria-hidden>◉</span>
            <b className="font-semibold">확인 필요</b>· {p.total}건 중 {p.waiting}건은 아이를 정하지 못했어요 ·
            <Link to="/queue/matching" className="font-semibold underline">
              매칭 확인하기
            </Link>
          </span>
        ) : null}

        {p.running > 0 ? (
          <span className="flex flex-wrap items-center gap-x-1.5 text-accentink">
            <Spinner size={15} />
            <b className="font-semibold">매칭 중</b>· {p.total}건 중 {p.resolved}건의 아이를 정했어요
          </span>
        ) : null}

        {handed > 0 ? (
          <span className={`flex flex-wrap items-center gap-x-1.5 ${p.tone === "done" ? "text-pass" : "text-[14px] text-ink2"}`}>
            <span aria-hidden>✓</span>
            {p.tone === "done" ? <b className="font-semibold">완료</b> : null}
            {p.tone === "done" ? "· " : null}
            {handed === p.total ? `모든 기록(${handed}건)이` : `기록 ${handed}건이`} 아이별 카드로 넘어갔어요 ·
            <Link to="/dashboard" className="text-accentink underline">
              오늘의 업무에서 보기
            </Link>
          </span>
        ) : null}

        {p.tone === "done" && handed === 0 ? (
          <span className="flex items-center gap-x-1.5 text-pass">
            <span aria-hidden>✓</span>
            <b className="font-semibold">완료</b>
          </span>
        ) : null}

        {stuck > 0 ? (
          <span className="flex flex-wrap items-center gap-x-1.5 text-[14px] text-ink2">
            <span aria-hidden>!</span>
            처리하다 멈춘 기록 {stuck}건 · 관리자가 확인해요
          </span>
        ) : null}

        {excluded > 0 ? (
          <span className="text-[14px] text-muted">우리 기관 아이가 아니라서 뺀 기록 {excluded}건</span>
        ) : null}
      </div>
    </li>
  );
}
