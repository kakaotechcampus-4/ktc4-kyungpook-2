import { Card, Note, PageHeader } from "@/components/ui";
import { getChildren } from "@/lib/api";

export default async function UploadPage() {
  const children = await getChildren();
  const active = children.filter((c) => c.status === "active");
  const pending = children.filter((c) => c.status === "pending_consent");

  return (
    <>
      <PageHeader
        title="기록 업로드"
        description="업로드하면 매칭 · 검증 · 요약이 자동으로 진행되고 Gate 1 승인 대기로 이동합니다"
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
              비워두면 Matching Agent 가 판단하고, 확신이 낮으면 확인 필요 큐로 보냅니다.
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
            <p className="text-[16px] font-semibold text-ink2">
              파일을 끌어다 놓거나 선택하세요
            </p>
            <p className="text-[14px] text-muted">사진 · 일지 · 특이사항 메모</p>
            <input type="file" multiple className="mt-2 text-[14px]" />
          </div>

          <button
            type="button"
            className="tap self-start rounded bg-accent px-5 text-[16px] font-semibold text-white hover:bg-accentink"
          >
            업로드하고 처리 시작
          </button>
        </form>
      </Card>

      {pending.length > 0 ? (
        <div className="mt-5">
          <Note>
            <b className="font-semibold">
              {pending.map((c) => c.name).join(", ")}
            </b>{" "}
            은(는) 아직 보호자 동의를 기다리는 중이라 기록을 올릴 수 없습니다. 동의가 완료되면
            업로드가 열립니다.
          </Note>
        </div>
      ) : null}
    </>
  );
}
