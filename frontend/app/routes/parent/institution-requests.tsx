import { CheckCircle2, TriangleAlert } from "lucide-react";
import { useLoaderData, useRevalidator } from "react-router";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { confirmInstitutionRequest, getInstitutionRequests, getParentChildren } from "@/lib/api";
import { readSelectedChildId } from "@/lib/selectedChild";

export async function clientLoader() {
  const kids = await getParentChildren();
  return { requests: await getInstitutionRequests(readSelectedChildId(kids)) };
}

export default function InstitutionRequestsPage() {
  const { requests } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();

  async function confirm(id: string) {
    await confirmInstitutionRequest(id);
    revalidator.revalidate();
  }

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader title="기관 요청사항" back />
      <p className="text-[15px] leading-7 text-ink2">
        기관에서 요청한 준비물과 확인 사항입니다. 확인하면 기관에도 함께 표시됩니다.
      </p>

      <div className="flex flex-col gap-4">
        {requests.map((req) => {
          const confirmed = req.status === "confirmed";
          return (
            <div
              key={req.id}
              className={`rounded-2xl px-4 py-4 ${
                confirmed
                  ? "border border-line"
                  : "border-y border-r border-l-4 border-line border-l-human bg-humansoft/40"
              }`}
            >
              <div className="mb-2.5 flex items-center gap-2.5">
                <InstitutionIcon type={req.institution.type} />
                <span className="font-bold">{req.institution.name}</span>
                <span
                  className={`ml-auto flex shrink-0 items-center gap-1 rounded-full px-2.5 py-1 text-[12px] font-bold ${
                    confirmed ? "bg-passsoft text-pass" : "bg-humansoft text-human"
                  }`}
                >
                  {confirmed ? (
                    <CheckCircle2 size={14} strokeWidth={2} />
                  ) : (
                    <TriangleAlert size={14} strokeWidth={2} />
                  )}
                  {confirmed ? "확인 완료" : "확인 필요"}
                </span>
              </div>
              <ul className="mb-3 flex flex-col gap-1 pl-1 text-[15px] leading-6">
                {req.items.map((item, i) => (
                  <li key={i} className="flex gap-1.5">
                    <span aria-hidden className="text-muted">
                      ◦
                    </span>
                    {item}
                  </li>
                ))}
              </ul>
              {!confirmed ? (
                <button
                  onClick={() => confirm(req.id)}
                  className="tap h-12 w-full rounded-2xl bg-accent text-[15px] font-bold text-white hover:bg-accentink"
                >
                  확인했어요
                </button>
              ) : null}
            </div>
          );
        })}
      </div>
    </div>
  );
}
