import { useState } from "react";
import { useNavigate } from "react-router";
import { Card, Note, PageHeader } from "@/components/ui";
import { ApiError, registerChild } from "@/lib/api";

type FormErrors = { externalId?: string; form?: string };

/** 생년월일 입력의 상한. 서버가 미래 날짜를 거부한다. toISOString 은 UTC 라 자정 무렵 하루가 어긋나서 직접 만든다 */
function todayLocal(): string {
  const d = new Date();
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${mm}-${dd}`;
}

export default function ChildRegisterPage() {
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [birthDate, setBirthDate] = useState("");
  const [externalId, setExternalId] = useState("");
  const [errors, setErrors] = useState<FormErrors>({});
  const [pending, setPending] = useState(false);

  async function handleRegister(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!name.trim() || !birthDate) return;
    setErrors({});
    setPending(true);
    try {
      await registerChild({
        name: name.trim(),
        birthDate,
        externalId: externalId.trim() || undefined,
      });
      // 목록이 새로 불러와지면서 방금 등록한 아이가 "보호자 동의 대기"로 보인다
      navigate("/children");
    } catch (err) {
      setPending(false);
      handleError(err);
    }
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

  return (
    <>
      <PageHeader
        title="아이 등록"
        description="등록한 아이는 보호자가 동의하면 기록을 올릴 수 있는 상태가 됩니다"
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
              <span className="text-[15px] font-semibold">
                기관 내부 아동 ID
                <span className="ml-1 font-normal text-muted">(선택)</span>
              </span>
              <input
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
            동의하기 전까지는 이 아이의 기록을 올릴 수 없습니다. 관리번호는 같은 이름의 아이를
            구분하는 데 쓰이며, 우리 기관 안에서 겹칠 수 없습니다.
          </Note>

          {errors.form ? (
            <p role="alert" className="rounded bg-blocksoft px-3 py-2 text-[14px] text-block">
              {errors.form}
            </p>
          ) : null}

          <button
            type="submit"
            disabled={pending}
            className="tap self-start rounded bg-accent px-5 text-[16px] font-semibold text-white hover:bg-accentink disabled:opacity-50"
          >
            {pending ? "등록 중…" : "등록하기"}
          </button>
        </form>
      </Card>
    </>
  );
}
