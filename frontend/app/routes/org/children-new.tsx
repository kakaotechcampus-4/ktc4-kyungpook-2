import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { InviteCodeCard } from "@/components/org/InviteCodeCard";
import { Card, Note, PageHeader } from "@/components/ui";
import { ApiError, issueInvitation, registerChild } from "@/lib/api";
import type { IssuedInvitation, RegisteredChild } from "@/lib/types";

type FormErrors = { externalId?: string; form?: string };

/** 생년월일 입력의 상한. 서버가 미래 날짜를 거부한다. toISOString 은 UTC 라 자정 무렵 하루가 어긋나서 직접 만든다 */
function todayLocal(): string {
  const d = new Date();
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${mm}-${dd}`;
}

/**
 * I-04 아이 등록 — 등록하면 서버가 보호자 초대 코드를 함께 발급한다 (api-spec O-11).
 * 기관은 코드나 링크를 카톡 등으로 보호자에게 직접 보낸다.
 */
export default function ChildRegisterPage() {
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [birthDate, setBirthDate] = useState("");
  const [externalId, setExternalId] = useState("");
  const [errors, setErrors] = useState<FormErrors>({});
  const [pending, setPending] = useState(false);
  const [registered, setRegistered] = useState<{
    child: RegisteredChild;
    /** 이번에 발급한 코드들, 최근 것이 먼저. 새로 발급해도 이전 코드는 살아 있다 */
    invitations: IssuedInvitation[];
  } | null>(null);

  async function handleRegister(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!name.trim() || !birthDate || !externalId.trim()) return;
    setErrors({});
    setPending(true);
    try {
      const { child, invitation } = await registerChild({
        name: name.trim(),
        birthDate,
        externalId: externalId.trim(),
      });
      setRegistered({ child, invitations: [invitation] });
    } catch (err) {
      handleError(err);
    } finally {
      setPending(false);
    }
  }

  async function handleReissue() {
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

  function startOver() {
    setRegistered(null);
    setName("");
    setBirthDate("");
    setExternalId("");
    setErrors({});
  }

  function handleError(err: unknown) {
    if (!(err instanceof ApiError)) {
      setErrors({ form: "네트워크 연결을 확인하고 다시 시도해 주세요." });
      return;
    }
    if (err.status === 401) {
      navigate("/login", { replace: true });
      return;
    }
    switch (err.code) {
      case "DUPLICATE_EXTERNAL_ID":
        setErrors({ externalId: "이미 사용 중인 관리번호입니다" });
        return;
      case "INVALID_REQUEST":
        setErrors({ form: "이름·생년월일·관리번호를 확인해 주세요." });
        return;
      case "INVITE_CODE_ISSUE_FAILED":
        // 등록 중이었다면 아이도 등록되지 않았다 (한 트랜잭션). 그대로 다시 누르면 된다.
        setErrors({
          form: registered
            ? "초대 코드를 만들지 못했어요. 다시 시도해 주세요."
            : "초대 코드를 만들지 못해 등록되지 않았어요. 다시 시도해 주세요.",
        });
        return;
      case "SIGNUP_NOT_COMPLETED":
        navigate("/signup", { replace: true });
        return;
      case "ORGANIZATION_NOT_ASSIGNED":
        setErrors({ form: "기관 계정만 아이를 등록할 수 있어요." });
        return;
    }
    if (err.status === 403) {
      setErrors({ form: "보안 확인에 실패했습니다. 새로고침한 뒤 다시 시도해 주세요." });
      return;
    }
    setErrors({ form: "잠시 후 다시 시도해 주세요." });
  }

  const inputClass =
    "tap rounded border border-line2 px-3 text-[16px] outline-none focus:border-accent aria-invalid:border-block";
  const formError = errors.form ? (
    <p role="alert" className="rounded bg-blocksoft px-3 py-2 text-[14px] text-block">
      {errors.form}
    </p>
  ) : null;

  if (registered) {
    const { child, invitations } = registered;
    return (
      <>
        <PageHeader
          title="아이 등록 완료"
          description="초대 코드나 링크를 보호자에게 카톡 등으로 직접 보내 주세요"
        />

        <Card>
          <div className="flex flex-col gap-5">
            <p className="text-[16px]">
              <b className="font-semibold">{child.name}</b>
              <span className="ml-2 text-[14px] text-muted tabular-nums">
                {child.birthDate} 생 · #{child.externalId}
              </span>
            </p>

            <div className="flex flex-col gap-3">
              {invitations.map((invitation) => (
                <InviteCodeCard key={invitation.id} invitation={invitation} />
              ))}
            </div>

            <Note>
              코드는 발급 후 <b className="font-semibold">10분</b> 동안 <b className="font-semibold">한 번</b>만
              쓸 수 있습니다. 보호자가 늦게 확인했다면 새 코드를 발급해 다시 보내 주세요. 새로
              발급해도 이전 코드는 그대로 쓸 수 있어, 보호자마다 따로 보낼 수 있습니다.
            </Note>

            {formError}

            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                onClick={handleReissue}
                disabled={pending}
                className="tap rounded bg-accent px-5 text-[16px] font-semibold text-white hover:bg-accentink disabled:opacity-50"
              >
                {pending ? "발급 중…" : "새 코드 발급"}
              </button>
              <Link
                to={`/children/${encodeURIComponent(child.id)}`}
                className="tap inline-flex items-center rounded border border-line2 px-5 text-[16px] font-semibold text-ink2 hover:bg-surface2"
              >
                아동 상세로
              </Link>
              <button
                type="button"
                onClick={startOver}
                className="tap rounded border border-line2 px-5 text-[16px] font-semibold text-ink2 hover:bg-surface2"
              >
                다른 아이 등록
              </button>
            </div>
          </div>
        </Card>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="아이 등록"
        description="등록하면 보호자에게 보낼 초대 코드가 함께 발급됩니다"
      />

      <Card>
        <form onSubmit={handleRegister} className="flex flex-col gap-5">
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="flex flex-col gap-1.5">
              <span className="text-[15px] font-semibold">이름</span>
              <input
                required
                value={name}
                onChange={(e) => setName(e.target.value)}
                maxLength={100}
                placeholder="김OO"
                className={inputClass}
              />
            </label>
            <label className="flex flex-col gap-1.5">
              <span className="text-[15px] font-semibold">생년월일</span>
              <input
                type="date"
                required
                value={birthDate}
                onChange={(e) => setBirthDate(e.target.value)}
                max={todayLocal()}
                className={inputClass}
              />
            </label>
            <label className="flex flex-col gap-1.5">
              <span className="text-[15px] font-semibold">기관 내부 아동 ID</span>
              <input
                required
                value={externalId}
                onChange={(e) => {
                  setExternalId(e.target.value);
                  if (errors.externalId) setErrors((prev) => ({ ...prev, externalId: undefined }));
                }}
                maxLength={50}
                placeholder="센터에서 쓰는 관리번호"
                aria-invalid={errors.externalId ? true : undefined}
                aria-describedby={errors.externalId ? "external-id-error" : undefined}
                className={inputClass}
              />
              {errors.externalId ? (
                <span id="external-id-error" className="text-[14px] text-block">
                  {errors.externalId}
                </span>
              ) : null}
            </label>
          </div>

          <Note>
            등록하면 <b className="font-semibold">보호자 동의 대기</b> 상태가 됩니다. 보호자가
            초대 코드로 연결하고 동의하기 전까지는 이 아이의 기록을 올릴 수 없습니다. 관리번호는
            같은 이름의 아이를 구분하는 데 쓰이며, 우리 기관 안에서 겹칠 수 없습니다.
          </Note>
          <Note>
            <b className="font-semibold">이미 다른 기관에 다니는 아이</b>는 새로 등록하지 말고 보호자에게{" "}
            <Link to="/children" className="font-semibold text-accentink underline">
              우리 기관 코드
            </Link>
            를 알려 주세요. 새로 등록하면 같은 아이가 둘이 되어 기관들의 기록이 한 아이에게 모이지 않습니다.
          </Note>

          {formError}

          <button
            type="submit"
            disabled={pending}
            className="tap self-start rounded bg-accent px-5 text-[16px] font-semibold text-white hover:bg-accentink disabled:opacity-50"
          >
            {pending ? "등록 중…" : "등록하고 초대 코드 발급"}
          </button>
        </form>
      </Card>
    </>
  );
}
