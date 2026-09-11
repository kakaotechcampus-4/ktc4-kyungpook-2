import { Card, Note, PageHeader } from "@/components/ui";

export default function ChildRegisterPage() {
  return (
    <>
      <PageHeader
        title="아이 등록 · 초대코드 발급"
        description="등록 후 초대코드를 보호자에게 전달하면, 보호자가 직접 동의하고 활성화합니다"
      />

      <Card className="mb-5">
        <form className="flex flex-col gap-5">
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="flex flex-col gap-1.5">
              <span className="text-[15px] font-semibold">이름</span>
              <input
                placeholder="김OO"
                className="tap rounded border border-line2 px-3 text-[16px] outline-none focus:border-accent"
              />
            </label>
            <label className="flex flex-col gap-1.5">
              <span className="text-[15px] font-semibold">생년월일</span>
              <input
                type="date"
                className="tap rounded border border-line2 px-3 text-[16px] outline-none focus:border-accent"
              />
            </label>
            <label className="flex flex-col gap-1.5">
              <span className="text-[15px] font-semibold">기관 내부 아동 ID</span>
              <input
                placeholder="센터에서 쓰는 관리번호"
                className="tap rounded border border-line2 px-3 text-[16px] outline-none focus:border-accent"
              />
            </label>
            <label className="flex flex-col gap-1.5">
              <span className="text-[15px] font-semibold">
                보호자 연락처 뒤 4자리
                <span className="ml-1 font-normal text-muted">(선택)</span>
              </span>
              <input
                inputMode="numeric"
                maxLength={4}
                placeholder="0000"
                className="tap rounded border border-line2 px-3 text-[16px] tabular-nums outline-none focus:border-accent"
              />
            </label>
          </div>

          <Note>
            보호자 전화번호 전체는 입력하지 않습니다. 뒤 4자리는 초대코드를 받은 사람이 실제
            보호자인지 대조하는 데만 쓰이고, 연락처는 보호자가 직접 입력합니다.
          </Note>

          <button
            type="button"
            className="tap self-start rounded bg-accent px-5 text-[16px] font-semibold text-white hover:bg-accentink"
          >
            등록하고 초대코드 발급
          </button>
        </form>
      </Card>

      <Card className="border-accent/40 bg-accentsoft">
        <p className="mb-1.5 text-[13px] font-semibold tracking-wider text-accentink uppercase">
          발급된 초대코드
        </p>
        <p className="mb-3 font-mono text-2xl font-bold tracking-[0.2em] text-accentink">
          ITDA-4K7M-92XQ
        </p>
        <p className="mb-3 text-[15px] leading-7 text-ink2">
          유효기간 <b className="font-semibold">7일</b> · 1회용입니다. 보호자에게 직접
          전달해주세요.
        </p>
        <div className="flex flex-wrap gap-2">
          <button className="tap rounded bg-accent px-4 text-[15px] font-semibold text-white">
            코드 복사
          </button>
          <button className="tap rounded border border-accent/50 bg-surface px-4 text-[15px] font-semibold text-accentink">
            초대 링크 복사
          </button>
          <button className="tap rounded border border-line2 bg-surface px-4 text-[15px] font-semibold text-ink2">
            재발급
          </button>
        </div>
        <p className="mt-3 text-[14px] text-muted">
          보호자가 동의를 완료하기 전까지 이 아이의 기록은 올릴 수 없습니다
          (<span className="font-mono text-[13px]">pending_consent</span>).
        </p>
      </Card>
    </>
  );
}
