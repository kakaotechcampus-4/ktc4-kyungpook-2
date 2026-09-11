import { useLoaderData } from "react-router";
import { PageHeader } from "@/components/ui";
import { getActivity } from "@/lib/api";

export async function clientLoader() {
  return { logs: await getActivity() };
}

export default function HistoryPage() {
  const { logs } = useLoaderData<typeof clientLoader>();

  return (
    <>
      <PageHeader title="업무 기록" description="승인 · 발송 · 조회 행위가 모두 기록됩니다" />

      <div className="overflow-x-auto rounded border border-line bg-surface">
        <table className="w-full min-w-[640px] border-collapse text-[15px]">
          <thead>
            <tr className="bg-surface2">
              {["시각", "행위자", "행위", "대상"].map((h) => (
                <th
                  key={h}
                  className="border-b border-line2 px-4 py-3 text-left text-[13px] font-semibold tracking-wider text-muted uppercase"
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {logs.map((l) => (
              <tr key={l.id} className="border-b border-line last:border-0">
                <td className="px-4 py-3 whitespace-nowrap text-muted tabular-nums">{l.at}</td>
                <td className="px-4 py-3 whitespace-nowrap font-medium">{l.actor}</td>
                <td className="px-4 py-3 whitespace-nowrap">{l.action}</td>
                <td className="px-4 py-3 text-ink2">{l.target}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
