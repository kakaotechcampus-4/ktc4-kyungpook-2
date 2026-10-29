import { useState, useTransition } from "react";
import { useNavigate, useRevalidator } from "react-router";
import { ApiError, addInstitutionByCode, isReady, lookupInstitutionByCode } from "@/lib/api";
import { InstitutionChip } from "@/components/ui";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentNotReadyPage } from "@/components/parent/NotReady";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { useChildContext } from "@/components/parent/ChildContext";
import type { Institution } from "@/lib/types";

/** 실연동 빌드에서 서버에 아직 없으면 mock 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function InstitutionAddPageRoute() {
  if (!isReady("institutionCode")) return <ParentNotReadyPage title="기관 직접 추가" back />;
  return <InstitutionAddPage />;
}

/**
 * "기관 직접 추가" — 보호자가 기관 코드로 아이를 다른 기관에 연결해 달라고 요청한다.
 *
 * 바로 연결되지 않는다. 기관이 수락하면 아이가 그 기관 명부에 오르고, 그때 "새 기관 권한 요청"
 * 알림으로 떠서 공유 범위를 정해 동의한다. 그래서 여기서는 공유 범위를 묻지 않는다.
 */
function InstitutionAddPage() {
  const { selected } = useChildContext();
  const [code, setCode] = useState("");
  const [found, setFound] = useState<Institution | null>(null);
  const [requested, setRequested] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const navigate = useNavigate();
  const revalidator = useRevalidator();

  function lookup(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    start(async () => {
      try {
        const institution = await lookupInstitutionByCode(code);
        if (institution) setFound(institution);
        else setError("일치하는 기관을 찾지 못했어요. 코드를 다시 확인해주세요.");
      } catch (err) {
        setError(messageOf(err));
      }
    });
  }

  function request() {
    setError(null);
    start(async () => {
      try {
        await addInstitutionByCode(selected.id, code);
        setRequested(true);
        // 설정 화면의 "수락 대기 중"이 바로 보이도록 아이 정보를 다시 읽는다
        revalidator.revalidate();
      } catch (err) {
        setError(messageOf(err));
      }
    });
  }

  if (found && requested) {
    return (
      <div className="flex flex-col gap-5">
        <ParentPageHeader title="연결을 요청했어요" back />
        <div className="flex items-center gap-3 rounded-2xl border border-line px-4 py-3">
          <InstitutionIcon type={found.type} />
          <InstitutionChip institution={found} withName />
        </div>
        <p className="text-[15px] leading-7 text-ink2">
          {found.name}이(가) 수락하면 알림으로 알려 드려요. 그때 어떤 정보를 공유할지 정하면 됩니다.
          수락 전에는 아무 정보도 공유되지 않습니다.
        </p>
        <button
          onClick={() => navigate("/parent/settings")}
          className="tap h-14 w-full rounded-2xl bg-accent text-[16px] font-bold text-white hover:bg-accentink"
        >
          설정으로 돌아가기
        </button>
      </div>
    );
  }

  if (found) {
    return (
      <div className="flex flex-col gap-5">
        <ParentPageHeader title={`${found.name}에 연결을 요청할까요`} back />
        <div className="flex items-center gap-3 rounded-2xl border border-line px-4 py-3">
          <InstitutionIcon type={found.type} />
          <InstitutionChip institution={found} withName />
        </div>
        <section className="rounded-2xl bg-surface2 px-4 py-4 text-[15px] leading-7 text-ink2">
          <p>
            {selected.name}을(를) 이 기관에 연결해 달라고 요청합니다. 기관이 수락하면 알림으로 알려 드려요.
          </p>
          <p className="mt-2">
            <b className="font-semibold text-ink">공유 범위는 그때 정해요.</b> 동의하기 전에는 아무 정보도
            공유되지 않습니다.
          </p>
        </section>
        {error ? (
          <p role="alert" className="rounded-2xl border border-block/40 bg-blocksoft px-4 py-3 text-[14px] text-block">
            {error}
          </p>
        ) : null}
        <div className="flex gap-2">
          <button
            onClick={() => navigate("/parent/settings")}
            className="tap h-14 flex-1 rounded-2xl border border-line2 text-[16px] font-bold text-ink2"
          >
            나중에
          </button>
          <button
            disabled={pending}
            onClick={request}
            className="tap h-14 flex-1 rounded-2xl bg-accent text-[16px] font-bold text-white hover:bg-accentink disabled:opacity-50"
          >
            {pending ? "요청 중…" : "연결 요청 보내기"}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader title="기관 직접 추가" back />
      <p className="text-[15px] leading-7 text-ink2">
        기관에서 받은 기관 코드를 입력해 주세요. 기관이 수락하면 연결됩니다.
      </p>
      <form onSubmit={lookup} className="flex flex-col gap-3">
        <label className="flex flex-col gap-1.5">
          <span className="text-[15px] font-semibold">기관 코드</span>
          <input
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder="XXXX-XXXX"
            required
            className="tap w-full rounded-2xl border border-line2 px-4 font-mono text-[16px] tracking-widest outline-none focus:border-accent"
          />
        </label>
        {error ? (
          <p role="alert" className="rounded-2xl border border-block/40 bg-blocksoft px-4 py-3 text-[14px] text-block">
            {error}
          </p>
        ) : null}
        <button
          disabled={pending}
          className="tap h-14 w-full rounded-2xl bg-accent px-4 text-[16px] font-bold text-white hover:bg-accentink disabled:opacity-50"
        >
          {pending ? "찾는 중…" : "다음"}
        </button>
      </form>
    </div>
  );
}

function messageOf(err: unknown): string {
  if (!(err instanceof ApiError)) return "네트워크 연결을 확인하고 다시 시도해 주세요.";
  switch (err.code) {
    case "INSTITUTION_CODE_NOT_FOUND":
      return "일치하는 기관을 찾지 못했어요. 코드를 다시 확인해주세요.";
    case "INSTITUTION_ALREADY_LINKED":
      return "이미 연결된 기관이에요. 설정의 기관 권한에서 확인해 주세요.";
  }
  if (err.status === 403) return "이 아이에 대한 권한이 없어요. 새로고침한 뒤 다시 시도해 주세요.";
  return "잠시 후 다시 시도해 주세요.";
}
