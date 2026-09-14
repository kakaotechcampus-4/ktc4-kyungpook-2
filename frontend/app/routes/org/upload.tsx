import { useEffect, useRef, useState } from "react";
import { useLoaderData } from "react-router";
import { Card, Note, PageHeader, PipelineStepper } from "@/components/ui";
import { getChildren } from "@/lib/api";
import { GATE1_INDEX, PIPELINE_STAGES } from "@/lib/pipeline";

export async function clientLoader() {
  const children = await getChildren();
  return {
    active: children.filter((c) => c.status === "active"),
    pending: children.filter((c) => c.status === "pending_consent"),
  };
}

/**
 * 프로토타입: 실제 업로드/파이프라인 API 연동 전까지, 클릭 한 번으로
 * 업로드 → 매칭 → 검증 → 요약 → Gate 1 대기까지의 진행 상황을 도식으로 시뮬레이션한다.
 * TODO: 실제 연동 시 이 setTimeout 시뮬레이션을 서버 상태 폴링/구독으로 교체한다.
 */
export default function UploadPage() {
  const { active, pending } = useLoaderData<typeof clientLoader>();
  const [runIndex, setRunIndex] = useState<number | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (runIndex === null || runIndex >= GATE1_INDEX) return;
    timerRef.current = setTimeout(() => {
      setRunIndex((i) => (i === null ? null : Math.min(i + 1, GATE1_INDEX)));
    }, 900);
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [runIndex]);

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
              disabled={runIndex !== null}
              className="tap rounded border border-line2 bg-surface px-3 text-[16px] outline-none focus:border-accent disabled:opacity-60"
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
              <select
                disabled={runIndex !== null}
                className="tap rounded border border-line2 bg-surface px-3 text-[16px] outline-none focus:border-accent disabled:opacity-60"
              >
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
                disabled={runIndex !== null}
                className="tap rounded border border-line2 bg-surface px-3 text-[16px] outline-none focus:border-accent disabled:opacity-60"
              />
            </label>
          </div>

          <div className="flex flex-col items-center gap-2 rounded border border-dashed border-line2 bg-paper px-6 py-10 text-center">
            <span aria-hidden className="text-2xl text-muted">
              ⬆
            </span>
            <p className="text-[16px] font-semibold text-ink2">파일을 끌어다 놓거나 선택하세요</p>
            <p className="text-[14px] text-muted">사진 · 일지 · 특이사항 메모</p>
            <input type="file" multiple disabled={runIndex !== null} className="mt-2 text-[14px]" />
          </div>

          {runIndex === null ? (
            <button
              type="button"
              onClick={() => setRunIndex(0)}
              className="tap self-start rounded bg-accent px-5 text-[16px] font-semibold text-white hover:bg-accentink"
            >
              등록하고 처리 시작
            </button>
          ) : (
            <div className="rounded border border-line bg-surface2 p-4">
              <PipelineStepper
                stages={[...PIPELINE_STAGES]}
                currentIndex={runIndex}
                gateIndex={GATE1_INDEX}
              />
              {runIndex >= GATE1_INDEX ? (
                <button
                  type="button"
                  onClick={() => setRunIndex(null)}
                  className="tap mt-4 rounded border border-line px-4 text-[14px] font-medium text-ink2 hover:border-accent"
                >
                  다른 기록 등록
                </button>
              ) : null}
            </div>
          )}
        </form>
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
