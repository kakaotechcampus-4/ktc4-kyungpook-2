import type { TermsDocument } from "@/lib/auth";

/** "2026-11-07" → "2026년 11월 7일" */
function koreanDate(ymd: string): string {
  const [y, m, d] = ymd.split("-").map(Number);
  return y && m && d ? `${y}년 ${m}월 ${d}일` : ymd;
}

/**
 * 약관 본문 한 개 — 시행일 · 초안 띠 · 본문. 보기 창(`TermsSheet`)과 약관 화면(`/terms/:key`)이 쓴다.
 * 제목은 부르는 쪽이 그린다(창은 머리에, 화면은 탭 아래에).
 * 본문은 서버가 주는 그냥 글이라 줄바꿈만 살린다 — 조항 번호와 `- ` 목록은 글자 그대로 보인다.
 */
export function TermsArticle({ doc }: { doc: TermsDocument }) {
  return (
    <div className="flex flex-col gap-4">
      <p className="text-[13px] text-muted">시행 {koreanDate(doc.effectiveDate)}</p>
      {doc.draft ? (
        <p className="rounded-xl bg-reviewsoft px-3.5 py-2.5 text-[14px] leading-6 text-review">
          <b className="font-bold">초안이에요.</b> 팀 검토 전 문안이라 빈칸과 바뀔 내용이 있어요.
        </p>
      ) : null}
      <div className="text-[15px] leading-7 whitespace-pre-wrap break-keep text-ink">{doc.body}</div>
    </div>
  );
}
