import { useState } from "react";
import { useNavigate } from "react-router";
import { InviteCodeCard } from "@/components/org/InviteCodeCard";
import { PopButton } from "@/components/org/WorkList";
import { Busy } from "@/components/ui";
import { ListPopup } from "@/components/ui/ListPopup";
import { ApiError, issueInvitation, registerChild } from "@/lib/api";
import type { IssuedInvitation, RegisteredChild } from "@/lib/types";

/** 생년월일 상한. 서버가 미래 날짜를 거부한다. toISOString 은 UTC 라 자정 무렵 하루가 어긋나서 직접 만든다 */
function todayLocal(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

const INPUT =
  "tap w-full rounded-[12px] border border-line2 bg-surface px-3 text-[16px] outline-none focus:border-accent aria-[invalid=true]:border-block";

/**
 * 아이 등록 (O-11) — 아동 목록 [+ 아이 등록] 팝업. 등록하면 서버가 보호자 초대 코드를 함께 발급해
 * 팝업이 그 코드를 보여 준다. 닫으면 `onRegistered` 로 목록을 새로 받는다.
 */
export function RegisterChildPopup({ onClose, onRegistered }: { onClose: () => void; onRegistered: () => void }) {
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [birthDate, setBirthDate] = useState("");
  const [externalId, setExternalId] = useState("");
  const [errors, setErrors] = useState<{ externalId?: string; form?: string }>({});
  const [pending, setPending] = useState(false);
  const [registered, setRegistered] = useState<{ child: RegisteredChild; invitations: IssuedInvitation[] } | null>(null);
  const ready = name.trim() !== "" && birthDate !== "" && externalId.trim() !== "";

  async function register(e?: React.FormEvent) {
    e?.preventDefault();
    if (!ready) return;
    setErrors({});
    setPending(true);
    try {
      const { child, invitation } = await registerChild({ name: name.trim(), birthDate, externalId: externalId.trim() });
      setRegistered({ child, invitations: [invitation] });
    } catch (err) {
      handleError(err);
    } finally {
      setPending(false);
    }
  }

  async function reissue() {
    if (!registered) return;
    setErrors({});
    setPending(true);
    try {
      const invitation = await issueInvitation(registered.child.id);
      setRegistered((r) => r && { ...r, invitations: [invitation, ...r.invitations] });
    } catch (err) {
      handleError(err);
    } finally {
      setPending(false);
    }
  }

  function handleError(err: unknown) {
    if (!(err instanceof ApiError)) return setErrors({ form: "네트워크 연결을 확인하고 다시 시도해 주세요." });
    if (err.status === 401) return navigate("/login", { replace: true });
    switch (err.code) {
      case "DUPLICATE_EXTERNAL_ID":
        return setErrors({ externalId: "이미 사용 중인 관리번호예요" });
      case "DUPLICATE_CHILD":
        return setErrors({ form: "같은 이름·생년월일의 아이가 이미 등록되어 있어요. 명부를 확인해 주세요." });
      case "INVALID_REQUEST":
        return setErrors({ form: "이름·생년월일·관리번호를 확인해 주세요." });
      case "INVITE_CODE_ISSUE_FAILED":
        // 등록 중이었다면 아이도 등록되지 않았다 (한 트랜잭션)
        return setErrors({
          form: registered ? "초대 코드를 만들지 못했어요. 다시 시도해 주세요." : "초대 코드를 만들지 못해 등록되지 않았어요. 다시 시도해 주세요.",
        });
      case "SIGNUP_NOT_COMPLETED":
        return navigate("/signup", { replace: true });
    }
    if (err.status === 403) return setErrors({ form: "보안 확인에 실패했어요. 새로고침한 뒤 다시 시도해 주세요." });
    setErrors({ form: "잠시 후 다시 시도해 주세요." });
  }

  function close() {
    if (registered) onRegistered();
    onClose();
  }

  if (registered) {
    return (
      <ListPopup
        title={`${registered.child.name} 등록했어요`}
        subtitle="이 코드를 보호자에게 전달해 주세요"
        busy={pending}
        onClose={close}
        footer={
          <>
            <PopButton onClick={reissue} disabled={pending}>
              {pending ? <Busy>발급 중…</Busy> : "새 코드 발급"}
            </PopButton>
            <span className="flex-1" />
            <PopButton tone="dark" onClick={close}>
              닫기
            </PopButton>
            {errors.form ? <span className="basis-full text-[14px] font-bold text-block">{errors.form}</span> : null}
          </>
        }
      >
        <div className="grid gap-3">
          {registered.invitations.map((inv) => (
            <InviteCodeCard key={inv.id} invitation={inv} />
          ))}
        </div>
      </ListPopup>
    );
  }

  return (
    <ListPopup
      title="아이 등록"
      busy={pending}
      onClose={onClose}
      footer={
        <>
          <PopButton onClick={onClose} disabled={pending}>
            취소
          </PopButton>
          <span className="flex-1" />
          {errors.form ? <span className="basis-full text-[14px] font-bold text-block sm:basis-auto">{errors.form}</span> : null}
          <PopButton tone="dark" onClick={() => register()} disabled={!ready || pending}>
            {pending ? <Busy>등록 중…</Busy> : "등록"}
          </PopButton>
        </>
      }
    >
      <form onSubmit={register} className="grid gap-4 sm:grid-cols-3">
        <label className="grid gap-1.5">
          <span className="text-[14px] font-bold text-ink2">이름</span>
          <input className={INPUT} value={name} maxLength={100} placeholder="김OO" onChange={(e) => setName(e.target.value)} autoFocus />
        </label>
        <label className="grid gap-1.5">
          <span className="text-[14px] font-bold text-ink2">생년월일</span>
          <input className={INPUT} type="date" max={todayLocal()} value={birthDate} onChange={(e) => setBirthDate(e.target.value)} />
        </label>
        <label className="grid gap-1.5">
          <span className="text-[14px] font-bold text-ink2">관리번호</span>
          <input
            className={INPUT}
            value={externalId}
            maxLength={50}
            placeholder="1105-003"
            aria-invalid={errors.externalId ? true : undefined}
            onChange={(e) => setExternalId(e.target.value)}
          />
          {errors.externalId ? <span className="text-[13px] font-bold text-block">{errors.externalId}</span> : null}
        </label>
        {/* 엔터로도 등록되게 */}
        <button type="submit" hidden />
      </form>
    </ListPopup>
  );
}
