import { Card, InstitutionChip, Note, PageHeader } from "@/components/ui";
import { MY_INSTITUTION } from "@/lib/mock/data";

export default function OrgSettingsPage() {
  return (
    <>
      <PageHeader
        title="기관 설정"
        description="기관 정보와 증빙서류 검증 상태입니다"
      />

      <Card className="mb-5">
        <form className="flex flex-col gap-5">
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="flex flex-col gap-1.5">
              <span className="text-[15px] font-semibold">기관명</span>
              <input
                defaultValue={MY_INSTITUTION.name}
                className="tap rounded border border-line2 px-3 text-[16px] outline-none focus:border-accent"
              />
            </label>
            <label className="flex flex-col gap-1.5">
              <span className="text-[15px] font-semibold">기관 유형</span>
              <select
                defaultValue="center"
                className="tap rounded border border-line2 bg-surface px-3 text-[16px] outline-none focus:border-accent"
              >
                <option value="school">학교</option>
                <option value="center">센터</option>
                <option value="assistant">활동지원사</option>
              </select>
            </label>
          </div>

          <div>
            <p className="mb-1.5 text-[15px] font-semibold">시설 인가 증빙서류</p>
            <div className="flex flex-wrap items-center gap-3 rounded border border-dashed border-line2 bg-paper px-4 py-4">
              <span className="text-[15px] text-ink2">인가증_2026.pdf</span>
              <span className="rounded bg-passsoft px-2 py-0.5 text-[13px] font-semibold text-pass">
                검증 완료
              </span>
              <button className="ml-auto tap rounded border border-line2 bg-surface px-3 text-[15px] font-semibold text-ink2">
                다시 올리기
              </button>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2 text-[15px]">
            <span className="font-semibold">현재 상태</span>
            <InstitutionChip institution={MY_INSTITUTION} withName />
            <span className="rounded bg-passsoft px-2 py-0.5 text-[13px] font-semibold text-pass">
              verified
            </span>
          </div>
        </form>
      </Card>

      <Note>
        증빙서류 검증이 끝나기 전에는 아이 등록과 초대코드 발급이 열리지 않습니다. 이 서류는
        보호자가 동의할 때 그대로 확인합니다.
      </Note>
    </>
  );
}
