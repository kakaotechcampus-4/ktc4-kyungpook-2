import { useEffect, useState } from "react";
import { ArrowRight, Bot } from "lucide-react";
import { Link, useLoaderData, useParams } from "react-router";
import { Card, EmptyState, PageHeader } from "@/components/ui";
import { getFileProgress, isReady } from "@/lib/api";
import { NotReadyPage } from "@/components/org/NotReady";
import { GATE1_INDEX, PIPELINE_STAGES } from "@/lib/pipeline";
import type { EntryProgress, FileProgress } from "@/lib/types";

const TITLE = "요약 진행";

export async function clientLoader({ params }: { params: { id?: string } }) {
  const files = await getFileProgress();
  return { file: files.find((f) => f.rawRecordId === params.id) ?? null };
}

/** 자동으로 움직이는 기록이 남아 있을 때만 이 간격으로 다시 묻는다 */
const POLL_MS = 3000;

const moving = (f: FileProgress) =>
  f.entries.some((e) => e.state === "running") || f.entries.length === 0;

/** 한 건이 지금 어디에 있는지 — 점 하나의 색 */
function dotClass(e: EntryProgress): string {
  if (e.state === "failed") return "bg-blocksoft text-block";
  if (e.state === "waiting") return "bg-reviewsoft text-review";
  if (e.state === "done" || e.stageIndex >= GATE1_INDEX) return "bg-accentsoft text-accentink";
  return "bg-ink text-paper";
}

/**
 * 요약 진행 — 디자인 캔버스 04-waiting.
 *
 * 기록 등록 화면의 막대가 "파일이 몇 %" 를 말한다면, 이 화면은 **지금 AI 가 무엇을 하고 있는지**를
 * 말한다. 요약은 한 건에 최대 90초가 걸려(`AI/summary/config.py`) 선생님이 빈 화면을 오래 본다.
 * 그래서 나가도 된다고 알려 주고(알림), 끝난 것부터 먼저 볼 길을 연다.
 */
export default function UploadProgressRoute() {
  if (!isReady("fileProgress")) return <NotReadyPage title={TITLE} />;
  return <UploadProgressPage />;
}

function UploadProgressPage() {
  const { id } = useParams();
  const { file: initial } = useLoaderData<typeof clientLoader>();
  const [file, setFile] = useState(initial);

  useEffect(() => {
    if (!file || !moving(file)) return;
    const timer = setInterval(async () => {
      try {
        const files = await getFileProgress();
        setFile(files.find((f) => f.rawRecordId === id) ?? null);
      } catch {
        // 다음 차례에 다시 묻는다
      }
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [file, id]);

  if (!file) {
    return (
      <>
        <PageHeader title={TITLE} />
        <EmptyState
          icon="◦"
          title="이 파일을 찾지 못했습니다"
          description="처리가 모두 끝나 목록에서 빠졌을 수 있습니다. 기록 등록 화면에서 확인해 주세요."
        />
      </>
    );
  }

  const total = file.entries.length;
  const done = file.entries.filter((e) => e.stageIndex >= GATE1_INDEX).length;
  const waiting = file.entries.filter((e) => e.state === "waiting").length;
  const failed = file.entries.filter((e) => e.state === "failed").length;
  const running = file.entries.find((e) => e.state === "running");

  // 지금 무슨 일이 일어나는지 한 줄. 가장 먼저 사람 손이 필요한 것부터 말한다
  const say =
    total === 0
      ? { head: "파일에서 기록을 떼어내고 있어요", sub: "한 파일에 여러 아이가 섞여 있어도 됩니다" }
      : failed > 0
        ? { head: `${failed}건이 멈췄어요`, sub: "나머지는 계속 진행됩니다" }
        : running
          ? {
              head: "AI가 기록을 정리하고 있어요",
              sub: `${PIPELINE_STAGES[running.stageIndex] ?? "요약"} 중 · 한 건에 최대 90초까지 걸립니다`,
            }
          : waiting > 0
            ? { head: `${waiting}건은 선생님 차례예요`, sub: "AI 가 혼자 넘기지 않습니다" }
            : { head: "모두 끝났어요", sub: "요약 검토에서 문장을 확인해 주세요" };

  return (
    <>
      <PageHeader
        eyebrow={file.fileName}
        title={TITLE}
        right={
          <span className="text-[16px] text-muted tabular-nums">
            <b className="font-extrabold text-ink">{done}</b> / {total}건
          </span>
        }
      />

      <Card className="flex flex-col items-center gap-7 py-10">
        {/* 아이 기록 ─ AI ─ 기관. 가운데만 움직인다 */}
        <div className="flex w-full max-w-lg items-center justify-between gap-2">
          <End src="/img/kid-04.png" label="아이 기록" />
          <Thread on={done > 0 || !!running} />
          <span className="relative flex size-24 shrink-0 items-center justify-center">
            {running ? (
              <span
                aria-hidden
                className="absolute inset-0 animate-spin rounded-full border-[3px] border-accentsoft border-t-accent"
                style={{ animationDuration: "1.6s" }}
              />
            ) : (
              <span aria-hidden className="absolute inset-0 rounded-full border-[3px] border-line" />
            )}
            <span className="flex size-16 items-center justify-center rounded-[18px] bg-reviewsoft text-review">
              <Bot aria-hidden size={30} strokeWidth={1.8} />
            </span>
          </span>
          <Thread on={done > 0} />
          <End src="/img/school.png" label="기관" />
        </div>

        <p
          role="status"
          className="flex flex-col items-center gap-1 rounded-[18px] bg-surface px-7 py-4 text-center shadow-[0_4px_18px_rgba(120,90,40,0.1)]"
        >
          <span className="text-[19px] font-extrabold text-ink">{say.head}</span>
          <span className="text-[14px] text-muted">{say.sub}</span>
        </p>

        {/* 건 하나에 점 하나 — 몇 번째가 어디까지 왔는지 */}
        {total > 0 ? (
          <ol aria-label="기록별 진행" className="flex max-w-xl flex-wrap justify-center gap-2">
            {file.entries.map((e, i) => (
              <li
                key={e.id}
                title={`${i + 1}번째 기록 · ${PIPELINE_STAGES[Math.min(e.stageIndex, GATE1_INDEX)]}`}
                className={`flex size-8 items-center justify-center rounded-full text-[13px] font-bold tabular-nums ${dotClass(e)}`}
              >
                {i + 1}
              </li>
            ))}
          </ol>
        ) : null}

        <div className="flex flex-wrap justify-center gap-2">
          <Link
            to="/upload"
            className="tap flex items-center rounded-full border border-line2 bg-surface px-5 text-[15px] font-bold text-ink2 hover:bg-surface2"
          >
            나가기 · 계속 진행돼요
          </Link>
          <Link
            to="/dashboard"
            className="tap flex items-center gap-1.5 rounded-full bg-ink px-6 text-[15px] font-bold text-paper hover:brightness-110"
          >
            {done > 0 ? `끝난 ${done}건 먼저 확인` : "대시보드로"}
            <ArrowRight aria-hidden size={16} strokeWidth={2.2} />
          </Link>
        </div>

        <p className="max-w-md text-center text-[13px] leading-6 text-muted">
          이 화면을 떠나도 처리는 계속됩니다. 요약이 끝난 기록은 <b className="font-bold">요약 검토</b>
          로 옮겨집니다.
        </p>
      </Card>
    </>
  );
}

function End({ src, label }: { src: string; label: string }) {
  return (
    <span className="flex shrink-0 flex-col items-center gap-2">
      <img
        src={src}
        alt=""
        aria-hidden
        width={72}
        height={72}
        className="size-[72px] rounded-[20px] bg-surface2 object-contain p-1.5"
      />
      <span className="text-[14px] font-bold text-ink2">{label}</span>
    </span>
  );
}

/** 두 끝을 잇는 실. 지나간 쪽만 초록이다 */
function Thread({ on }: { on: boolean }) {
  return (
    <span
      aria-hidden
      className={`mb-7 h-[3px] min-w-6 flex-1 rounded-full ${on ? "bg-accent" : "bg-line"}`}
    />
  );
}
