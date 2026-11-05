import { useEffect, useState } from "react";
import { Check, ChevronDown, Clock, FileText, LoaderCircle, TriangleAlert } from "lucide-react";
import { Link, useRevalidator } from "react-router";
import type { StageState } from "@/components/ui";
import {
  FILE_STAGE_SHORT,
  FILE_STAGES,
  type FileTone,
  fileProgressOf,
  fileStageStatesOf,
  isBeforeChild,
} from "@/lib/pipeline";
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

/* ── 상태 한 낱말 ───────────────────────────────────── */

type Bucket = "done" | "running" | "waiting" | "failed";

const BUCKET: Record<FileTone, Bucket> = {
  done: "done",
  splitting: "running",
  running: "running",
  waiting: "waiting",
  failed: "failed",
};

const STATUS: Record<Bucket, { label: string; chip: string; bar: string; icon: typeof Check }> = {
  done: { label: "완료", chip: "bg-passsoft text-pass", bar: "bg-pass", icon: Check },
  running: {
    label: "처리 중",
    chip: "bg-accentsoft text-accentink",
    bar: "bg-accent progress-stripes",
    icon: LoaderCircle,
  },
  // 막대에는 캔버스의 꽉 찬 노랑. 진한 노랑(human)은 글자용이다
  waiting: { label: "확인 필요", chip: "bg-humansoft text-human", bar: "bg-mark", icon: Clock },
  failed: { label: "멈춤", chip: "bg-blocksoft text-block", bar: "bg-block", icon: TriangleAlert },
};

/** 세부 단계 동그라미 — 색만으로 전하지 않도록 읽기 프로그램에는 낱말을 준다 */
const STEP: Record<StageState, { ring: string; word: string }> = {
  done: { ring: "bg-pass text-white", word: "완료" },
  active: { ring: "bg-accent text-white", word: "진행 중" },
  waiting: { ring: "bg-mark text-markink", word: "사람 확인 대기" },
  failed: { ring: "bg-block text-white", word: "실패" },
  pending: { ring: "border border-line2 text-line2", word: "대기 전" },
};

const time = (iso: string) => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? ""
    : `${String(d.getMonth() + 1).padStart(2, "0")}.${String(d.getDate()).padStart(2, "0")} ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
};

const bucketOf = (f: FileProgress) => BUCKET[fileProgressOf(f.entries).tone];

/**
 * 파일 단위 처리 현황 — "올린 파일이 잘 들어갔나, 아이가 정해졌나".
 *
 * 파일 한 개에 여러 아이 기록이 섞여 있어, 아이가 정해진 기록은 오늘의 업무의 아이별 카드(O-34)로
 * 넘겨준다. 그 뒤 검증 · 요약 · 요약 검토는 거기서 이어진다.
 *
 * 한 줄에 한 파일, 칸은 파일명 · 기록 수 · 상태 · 세부 단계 · 진행률 · 올린 시각. 무엇을 해야 하는지
 * (매칭 확인으로 가기 등)는 줄을 펼쳐야 나온다 — 모든 줄에 깔면 표가 글 더미가 된다.
 *
 * 실패에는 버튼을 두지 않는다. 매칭 실패는 확인 필요 큐로 가고(api-spec O-26), 재처리 API(O-27)는 없다.
 */
export function FileProgressList({ files }: { files: FileProgress[] }) {
  const [filter, setFilter] = useState<Bucket | "all">("all");

  const tabs: { key: Bucket | "all"; label: string; count: number }[] = [
    { key: "all" as const, label: "전체", count: files.length },
    ...(["done", "running", "waiting", "failed"] as const).map((b) => ({
      key: b,
      label: STATUS[b].label,
      count: files.filter((f) => bucketOf(f) === b).length,
    })),
  ].filter((t) => t.key === "all" || t.count > 0);

  const shown = filter === "all" ? files : files.filter((f) => bucketOf(f) === filter);

  return (
    <div className="flex flex-col gap-4">
      <div role="group" aria-label="상태로 거르기" className="flex flex-wrap gap-1.5">
        {tabs.map((t) => {
          const on = filter === t.key;
          return (
            <button
              key={t.key}
              type="button"
              aria-pressed={on}
              onClick={() => setFilter(t.key)}
              className={`tap flex items-center gap-1.5 rounded-full px-4 text-[15px] font-bold ${
                on ? "bg-ink text-paper" : "border border-line2 bg-surface text-ink2 hover:bg-surface2"
              }`}
            >
              {t.label}
              <span className="tabular-nums opacity-70">{t.count}</span>
            </button>
          );
        })}
      </div>

      {/* 좁은 화면에서는 표가 가로로 밀린다 — 페이지가 아니라 이 상자 안에서 */}
      <div className="overflow-x-auto rounded-[18px] border border-line">
        <table className="w-full min-w-[720px] border-collapse text-left">
          <thead>
            <tr className="bg-surface2/60 text-[13px] font-bold text-muted">
              <th scope="col" className="py-3 pr-3 pl-5 font-bold">파일명</th>
              <th scope="col" className="px-3 py-3 font-bold whitespace-nowrap">기록 수</th>
              <th scope="col" className="px-3 py-3 font-bold whitespace-nowrap">진행 상태</th>
              <th scope="col" className="px-3 py-3 font-bold whitespace-nowrap">세부 단계</th>
              <th scope="col" className="px-3 py-3 font-bold whitespace-nowrap">진행률</th>
              <th scope="col" className="px-3 py-3 font-bold whitespace-nowrap">올린 시각</th>
              <th scope="col" className="py-3 pr-5 pl-3"><span className="sr-only">자세히</span></th>
            </tr>
          </thead>
          <tbody>
            {shown.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-5 py-12 text-center text-[15px] text-muted">
                  이 상태인 파일이 없어요.
                </td>
              </tr>
            ) : (
              shown.map((f) => <FileRow key={f.rawRecordId} file={f} />)
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function FileRow({ file }: { file: FileProgress }) {
  const [open, setOpen] = useState(false);
  const { entries } = file;
  const p = fileProgressOf(entries);
  const states = fileStageStatesOf(entries);
  const bucket = BUCKET[p.tone];
  const st = STATUS[bucket];
  const Icon = st.icon;

  const early = entries.filter(isBeforeChild);
  // 매칭 단계에서 끝난 기록은 "우리 기관 아이 아님"으로 뺀 것뿐이다 (BE EXCLUDED → done)
  const excluded = early.filter((e) => e.state === "done").length;
  // 아이가 정해진 뒤 검증 · 요약에서 멈춘 기록은 넘어간 것으로 치지 않는다 — 카드에서는 "관리자 확인 중"이다
  const later = entries.filter((e) => !isBeforeChild(e));
  const stuck = later.filter((e) => e.state === "failed").length;
  const handed = later.length - stuck - p.blocked;

  return (
    <>
      <tr className="border-t border-line align-middle">
        <td className="py-3.5 pr-3 pl-5">
          <span className="flex items-center gap-2.5">
            <span
              aria-hidden
              className="flex size-9 shrink-0 items-center justify-center rounded-[10px] bg-surface2 text-ink2"
            >
              <FileText size={17} strokeWidth={1.9} />
            </span>
            <span className="text-[15px] font-bold break-all">{file.fileName}</span>
          </span>
        </td>
        <td className="px-3 py-3.5 text-[14px] whitespace-nowrap text-ink2 tabular-nums">
          {p.tone === "splitting" ? "—" : `${p.total}건`}
        </td>
        <td className="px-3 py-3.5">
          <span
            className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-[13px] font-bold whitespace-nowrap ${st.chip}`}
          >
            <Icon
              aria-hidden
              size={13}
              strokeWidth={2.4}
              className={bucket === "running" ? "animate-spin" : ""}
            />
            {st.label}
          </span>
        </td>
        <td className="px-3 py-3.5">
          <ol className="flex items-start gap-1">
            {FILE_STAGES.map((stage, i) => (
              <li key={stage} className="flex items-start gap-1">
                {i > 0 ? (
                  <span
                    aria-hidden
                    className={`mt-3 h-px w-3 ${states[i - 1] === "done" ? "bg-pass" : "bg-line2"}`}
                  />
                ) : null}
                <span className="flex w-12 flex-col items-center gap-1">
                  <span
                    className={`flex size-6 items-center justify-center rounded-full ${STEP[states[i]].ring}`}
                  >
                    <span className="sr-only">
                      {stage} {STEP[states[i]].word}
                    </span>
                    {states[i] === "done" ? (
                      <Check aria-hidden size={13} strokeWidth={3} />
                    ) : states[i] === "active" ? (
                      <LoaderCircle aria-hidden size={13} strokeWidth={3} className="animate-spin" />
                    ) : states[i] === "waiting" ? (
                      <Clock aria-hidden size={12} strokeWidth={2.6} />
                    ) : states[i] === "failed" ? (
                      <TriangleAlert aria-hidden size={12} strokeWidth={2.6} />
                    ) : (
                      <span aria-hidden className="size-1.5 rounded-full bg-line2" />
                    )}
                  </span>
                  {/* 단계 이름을 늘 보이게 한다 — 전에는 마우스를 올려야 툴팁으로만 나왔다 (#359) */}
                  <span aria-hidden className="text-[12px] leading-4 whitespace-nowrap text-muted">
                    {FILE_STAGE_SHORT[i]}
                  </span>
                </span>
              </li>
            ))}
          </ol>
        </td>
        <td className="px-3 py-3.5">
          <span className="flex items-center gap-2.5">
            <span
              role="progressbar"
              aria-label={`${file.fileName} 진행률`}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={p.percent}
              className="block h-2 w-24 overflow-hidden rounded-full bg-surface2"
            >
              <span
                className={`block h-full rounded-full transition-[width] duration-500 ease-out ${st.bar}`}
                style={{ width: `${p.percent}%` }}
              />
            </span>
            <span className="text-[14px] font-bold tabular-nums">{p.percent}%</span>
          </span>
        </td>
        <td className="px-3 py-3.5 text-[14px] whitespace-nowrap text-muted tabular-nums">
          {time(file.uploadedAt)}
        </td>
        <td className="py-3.5 pr-5 pl-3 text-right">
          <button
            type="button"
            aria-expanded={open}
            onClick={() => setOpen((v) => !v)}
            className="flex size-8 items-center justify-center rounded-full text-muted hover:bg-surface2 hover:text-ink"
          >
            <span className="sr-only">{file.fileName} 자세히 {open ? "접기" : "펼치기"}</span>
            <ChevronDown
              aria-hidden
              size={17}
              strokeWidth={2.2}
              className={`transition-transform duration-200 ${open ? "rotate-180" : ""}`}
            />
          </button>
        </td>
      </tr>

      {open ? (
        <tr className="border-t border-line bg-surface2/40">
          <td colSpan={7} className="px-5 py-4">
            <ul className="flex flex-col gap-1.5 text-[14px] leading-6">
              {p.tone === "splitting" ? (
                <li className="text-accentink">파일을 날짜별 기록으로 나누는 중이에요.</li>
              ) : null}
              {p.failed > 0 ? (
                <li className="text-block">
                  <b className="font-bold">매칭 실패 {p.failed}건</b> — AI 판정을 받지 못했어요.{" "}
                  <Link to="/queue/matching" className="font-bold underline">
                    매칭 확인에서 고르기
                  </Link>
                </li>
              ) : null}
              {p.waiting > 0 ? (
                <li className="text-human">
                  <b className="font-bold">확인 필요 {p.waiting}건</b> — {p.total}건 중 아이를 정하지
                  못했어요.{" "}
                  <Link to="/queue/matching" className="font-bold underline">
                    매칭 확인하기
                  </Link>
                </li>
              ) : null}
              {p.running > 0 ? (
                <li className="text-accentink">
                  매칭 중 — {p.total}건 중 {p.resolved}건의 아이를 정했어요.
                </li>
              ) : null}
              {p.blocked > 0 ? (
                <li className="text-human">
                  <b className="font-bold">수정 요청 {p.blocked}건</b> — 검증에서 멈췄어요. 고치거나 보류해야
                  넘어가요.{" "}
                  <Link to="/queue/reinput" className="font-bold underline">
                    수정 요청에서 고치기
                  </Link>
                </li>
              ) : null}
              {handed > 0 ? (
                <li className="text-ink2">
                  기록 {handed}건이 아이별 카드로 넘어갔어요.{" "}
                  <Link to="/dashboard" className="font-bold text-accentink underline">
                    대시보드에서 보기
                  </Link>
                </li>
              ) : null}
              {stuck > 0 ? (
                <li className="text-ink2">처리하다 멈춘 기록 {stuck}건 · 관리자가 확인해요.</li>
              ) : null}
              {excluded > 0 ? (
                <li className="text-muted">우리 기관 아이가 아니라서 뺀 기록 {excluded}건.</li>
              ) : null}
              <li className="text-muted">
                {FILE_STAGES.map((s, i) => `${s} ${STEP[states[i]].word}`).join(" · ")}
              </li>
            </ul>
          </td>
        </tr>
      ) : null}
    </>
  );
}
