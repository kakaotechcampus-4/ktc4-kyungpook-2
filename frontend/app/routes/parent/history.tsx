import { useChildContext } from "@/components/parent/ChildContext";
import { ParentNotReadyPage } from "@/components/parent/NotReady";
import { PARENT_ACTIVITY } from "@/lib/mock/data";
import { isReady } from "@/lib/api";

/** 실연동 빌드에서 서버에 아직 없으면 mock 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function ParentHistoryPageRoute() {
  if (!isReady("guardianJournal")) return <ParentNotReadyPage title="활동 이력" />;
  return <ParentHistoryPage />;
}

function ParentHistoryPage() {
  const { selected } = useChildContext();
  const activity = PARENT_ACTIVITY[selected.id] ?? [];

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="mb-1 text-[20px] font-bold tracking-tight">활동 이력</h1>
        <p className="text-[15px] leading-6 text-muted">
          언제, 어느 기관에, 무엇이 전달되었는지 확인할 수 있어요.
        </p>
      </div>

      {activity.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-line2 px-4 py-6 text-center text-[14px] text-muted">
          아직 공유된 활동이 없습니다.
        </p>
      ) : (
        <ul className="flex flex-col divide-y divide-line rounded-2xl border border-line">
          {activity.map((a) => (
            <li key={a.id} className="px-4 py-3">
              <p className="text-[15px] leading-6">{a.text}</p>
              <p className="mt-0.5 text-[13px] text-muted">{a.at}</p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
