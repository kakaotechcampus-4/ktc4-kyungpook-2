import Link from "next/link";
import { Card, ConsentStatus, InstitutionChip, PageHeader } from "@/components/ui";
import { getChildren } from "@/lib/api";

const STATUS_LABEL = {
  active: { text: "이용 중", cls: "text-pass bg-passsoft" },
  pending_consent: { text: "보호자 동의 대기", cls: "text-review bg-reviewsoft" },
  suspended: { text: "동의 철회됨", cls: "text-block bg-blocksoft" },
} as const;

export default async function ChildrenPage() {
  const children = await getChildren();

  return (
    <>
      <PageHeader
        title="아동 관리"
        description="담당 아이와 연결된 기관, 보호자 동의 상태를 확인합니다"
        right={
          <Link
            href="/children/new"
            className="tap inline-flex items-center rounded bg-accent px-4 text-[15px] font-semibold text-white hover:bg-accentink"
          >
            아이 등록
          </Link>
        }
      />

      <div className="flex flex-col gap-4">
        {children.map((c) => {
          const s = STATUS_LABEL[c.status];
          return (
            <Card key={c.id}>
              <div className="mb-3 flex flex-wrap items-center gap-2.5">
                <span
                  aria-hidden
                  className="flex size-9 items-center justify-center rounded-full bg-surface2 text-[15px] font-semibold text-ink2"
                >
                  {c.name.slice(0, 1)}
                </span>
                <Link
                  href={`/children/${c.id}`}
                  className="text-[17px] font-bold hover:text-accentink hover:underline"
                >
                  {c.name}
                </Link>
                <span className="text-[14px] text-muted tabular-nums">{c.birthDate}</span>
                <span
                  className={`ml-auto rounded px-2 py-0.5 text-[13px] font-semibold ${s.cls}`}
                >
                  {s.text}
                </span>
              </div>

              <p className="mb-2 text-[13px] font-semibold tracking-wider text-muted uppercase">
                연결된 기관
              </p>
              <ul className="flex flex-wrap gap-x-5 gap-y-2">
                {c.institutions.map(({ institution, consent }) => (
                  <li
                    key={institution.id}
                    className="flex items-center gap-2 text-[15px]"
                  >
                    <InstitutionChip institution={institution} withName />
                    <ConsentStatus state={consent} />
                  </li>
                ))}
              </ul>
            </Card>
          );
        })}
      </div>
    </>
  );
}
