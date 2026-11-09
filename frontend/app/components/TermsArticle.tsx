import type { TermsDocument } from "@/lib/auth";

function koreanDate(ymd: string): string {
  const [y, m, d] = ymd.split("-").map(Number);
  return y && m && d ? `${y}년 ${m}월 ${d}일` : ymd;
}

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
