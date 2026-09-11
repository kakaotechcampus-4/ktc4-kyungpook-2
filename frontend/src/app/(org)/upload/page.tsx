import { Card, Note, PageHeader } from "@/components/ui";
import { getChildren } from "@/lib/api";
import { UploadForm } from "./UploadForm";

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
        <UploadForm activeChildren={active} />
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
