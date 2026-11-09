import { useState } from "react";
import { WithdrawAccount } from "@/components/WithdrawAccount";
import { Busy } from "@/components/ui";
import { redirect, useLoaderData, useNavigate } from "react-router";
import { ApiError } from "@/lib/apiError";
import {
  getSession,
  homePathFor,
  withdrawTargetOf,
  signup,
  type OrganizationType,
  type SignupInput,
} from "@/lib/auth";

/**
 * 회원가입 — 카카오 로그인만 하고 역할을 안 고른 사람(`/auth/me` 의 signupCompleted=false)이 온다.
 *
 * 이메일·비밀번호는 없다. 역할을 고르고, 기관이면 기관 정보를 넣는 것까지가 가입이다
 * (docs/api/api-spec.md §2.6). 역할은 한 번 정하면 바뀌지 않는다.
 *
 * `?role=org|parent` 는 미리 골라둘 역할이다. 로그인 전에 누른 버튼(`/oauth/success`)이나
 * 가입 전에 들어가려던 화면(각 가드)이 붙여준다. 사용자가 바꿀 수 있다.
 */
export async function clientLoader({ request }: { request: Request }) {
  const session = await getSession();
  if (!session.loggedIn) return redirect("/login");
  if (session.withdrawing) return redirect("/withdrawal");
  if (session.signupCompleted && session.role) {
    return redirect(homePathFor(session.role, session.termsAgreed));
  }

  const hint = new URL(request.url).searchParams.get("role");
  const initialRole: "org" | "parent" | null =
    hint === "org" || hint === "parent" ? hint : null;
  return { name: session.name, initialRole, withdrawTarget: withdrawTargetOf(session) };
}

const ORG_TYPES: { value: OrganizationType; label: string }[] = [
  { value: "SCHOOL", label: "학교" },
  { value: "CENTER", label: "센터" },
  { value: "ACTIVITY_SUPPORT", label: "활동지원사" },
];

const ROLES = [
  { value: "org", label: "기관 담당자", description: "학교·센터·활동지원 기관에서 기록을 올려요" },
  { value: "parent", label: "보호자", description: "아이의 기록을 모아 보고 공유를 허락해요" },
] as const;

type Errors = { organizationName?: string; businessNumber?: string; form?: string };

export default function SignupPage() {
  const { name, initialRole, withdrawTarget } = useLoaderData<typeof clientLoader>();
  const navigate = useNavigate();

  const [role, setRole] = useState<"org" | "parent" | null>(initialRole);
  const [organizationName, setOrganizationName] = useState("");
  const [organizationType, setOrganizationType] = useState<OrganizationType>("CENTER");
  const [businessNumber, setBusinessNumber] = useState("");
  const [errors, setErrors] = useState<Errors>({});
  const [pending, setPending] = useState(false);

  function validate(): Errors {
    if (role !== "org") return {};
    const next: Errors = {};
    const trimmed = organizationName.trim();
    if (!trimmed) next.organizationName = "기관명을 입력해 주세요";
    else if (trimmed.length > 100) next.organizationName = "기관명은 100자 이내로 입력해 주세요";
    if (!/^\d{10}$/.test(businessNumber)) next.businessNumber = "숫자 10자리를 입력해 주세요";
    return next;
  }

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!role || pending) return;

    const invalid = validate();
    setErrors(invalid);
    if (Object.keys(invalid).length > 0) return;

    // 보호자는 role 만 보낸다 — 기관 칸에 쓰다 만 값이 있어도 싣지 않는다(실으면 400).
    const input: SignupInput =
      role === "parent"
        ? { role: "parent" }
        : {
            role: "org",
            organizationName: organizationName.trim(),
            organizationType,
            businessNumber,
          };

    setPending(true);
    try {
      const session = await signup(input);
      navigate(homePathFor(session.role ?? role, session.termsAgreed), { replace: true });
    } catch (err) {
      setPending(false);
      await handleError(err);
    }
  }

  async function handleError(err: unknown) {
    if (!(err instanceof ApiError)) {
      setErrors({ form: "네트워크 연결을 확인하고 다시 시도해 주세요." });
      return;
    }
    if (err.status === 401) {
      navigate("/login", { replace: true });
      return;
    }
    switch (err.code) {
      case "DUPLICATE_BUSINESS_NUMBER":
        setErrors({ businessNumber: "이미 등록된 사업자등록번호입니다" });
        return;
      case "ALREADY_SIGNED_UP": {
        // 다른 탭에서 먼저 끝낸 경우 등. 역할은 서버에 이미 정해져 있으니 그쪽으로 보낸다.
        const session = await getSession().catch(() => null);
        navigate(session?.role ? homePathFor(session.role, session.termsAgreed) : "/login", {
          replace: true,
        });
        return;
      }
      case "INVALID_REQUEST":
        setErrors({ form: "입력한 내용을 다시 확인해 주세요." });
        return;
    }
    if (err.status === 403) {
      setErrors({ form: "보안 확인에 실패했습니다. 새로고침한 뒤 다시 시도해 주세요." });
      return;
    }
    setErrors({ form: "잠시 후 다시 시도해 주세요." });
  }

  const inputClass =
    "tap rounded border border-line2 bg-surface px-3 text-[16px] outline-none focus:border-accent aria-invalid:border-block";

  return (
    <div
      className="flex min-h-screen items-center justify-center px-4 py-10 break-keep"
      /*
        기관·보호자 캔버스와 같은 두 겹 바탕.

        이 화면은 **둘 중 어느 묶음에도 안 들어간다** — 역할을 고르는 자리라
        아직 기관인지 보호자인지 모른다. 그래서 org.css 도 parent.css 도 닿지 않아
        여기만 맨 크림으로 남아 있었다 (로그인 → 이 화면 → 초대 순으로 보면 가운데
        한 장만 색이 빠진다). 두 캔버스가 같은 바탕을 쓰므로 여기에 직접 깐다.
      */
      style={{
        background:
          "radial-gradient(620px 380px at 100% 0%, #fff0b4 0%, rgb(255 240 180 / 0) 64%)," +
          "radial-gradient(560px 460px at 0% 100%, #ddf2e3 0%, rgb(221 242 227 / 0) 64%)," +
          "var(--color-paper)",
      }}
    >
      <div className="w-full max-w-[460px]">
        <div className="mb-7 flex flex-col items-center gap-1.5 text-center">
          <img src="/img/logo.png" alt="잇다" width={600} height={220} className="h-auto w-[116px]" />
          <p className="text-[14px] text-muted">회원가입</p>
        </div>

        <form
          noValidate
          onSubmit={handleSubmit}
          className="flex flex-col gap-5 rounded-[22px] border border-line bg-surface p-6 shadow-[0_10px_30px_rgba(120,90,40,0.07)]"
        >
          <div>
            <h1 className="mb-1.5 text-[20px] font-bold tracking-tight">
              {name ? `${name}님, 어떤 분이신가요?` : "어떤 분이신가요?"}
            </h1>
            <p className="text-[15px] leading-7 text-ink2">
              고른 역할에 따라 보이는 화면이 달라져요. 가입한 뒤에는 바꿀 수 없어요.
            </p>
          </div>

          <fieldset className="flex flex-col gap-2">
            <legend className="sr-only">역할</legend>
            {ROLES.map((r) => (
              <label
                key={r.value}
                className="tap flex cursor-pointer items-center gap-3 rounded-[18px] border border-line2 px-4 py-3 has-checked:border-accent has-checked:bg-accentsoft"
              >
                <input
                  type="radio"
                  name="role"
                  value={r.value}
                  checked={role === r.value}
                  onChange={() => {
                    setRole(r.value);
                    setErrors({});
                  }}
                  className="size-5 shrink-0 accent-accent"
                />
                <span className="flex flex-col">
                  <span className="text-[16px] font-semibold">{r.label}</span>
                  <span className="text-[14px] text-muted">{r.description}</span>
                </span>
              </label>
            ))}
          </fieldset>

          {role === "org" ? (
            <div className="flex flex-col gap-4">
              <label className="flex flex-col gap-1.5">
                <span className="text-[15px] font-semibold">기관명</span>
                <input
                  value={organizationName}
                  onChange={(e) => setOrganizationName(e.target.value)}
                  maxLength={100}
                  placeholder="햇살아동발달센터"
                  aria-invalid={errors.organizationName ? true : undefined}
                  aria-describedby={errors.organizationName ? "org-name-error" : undefined}
                  className={inputClass}
                />
                {errors.organizationName ? (
                  <span id="org-name-error" className="text-[14px] text-block">
                    {errors.organizationName}
                  </span>
                ) : null}
              </label>

              <label className="flex flex-col gap-1.5">
                <span className="text-[15px] font-semibold">기관 유형</span>
                <select
                  value={organizationType}
                  onChange={(e) => setOrganizationType(e.target.value as OrganizationType)}
                  className={inputClass}
                >
                  {ORG_TYPES.map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </label>

              <label className="flex flex-col gap-1.5">
                <span className="text-[15px] font-semibold">사업자등록번호</span>
                <input
                  value={businessNumber}
                  // 하이픈을 섞어 붙여넣어도 숫자만 남긴다. BE 는 숫자 10자리만 받는다.
                  onChange={(e) => setBusinessNumber(e.target.value.replace(/\D/g, "").slice(0, 10))}
                  inputMode="numeric"
                  autoComplete="off"
                  placeholder="숫자 10자리 ('-' 없이)"
                  aria-invalid={errors.businessNumber ? true : undefined}
                  aria-describedby={errors.businessNumber ? "biz-number-error" : undefined}
                  className={`${inputClass} tabular-nums`}
                />
                {errors.businessNumber ? (
                  <span id="biz-number-error" className="text-[14px] text-block">
                    {errors.businessNumber}
                  </span>
                ) : null}
              </label>
            </div>
          ) : null}

          {errors.form ? (
            <p role="alert" className="rounded bg-blocksoft px-3 py-2 text-[14px] text-block">
              {errors.form}
            </p>
          ) : null}

          <button
            type="submit"
            disabled={!role || pending}
            className="tap w-full rounded bg-ink px-4 text-[16px] font-semibold text-paper hover:brightness-110 disabled:bg-surface2 disabled:text-muted"
          >
            {pending ? <Busy>가입하는 중…</Busy> : "가입하기"}
          </button>
        </form>
        {/* 역할을 고르기 전에도 떠날 수 있어야 한다 — 카카오 연결을 끊고 가입 정보를 지운다 */}
        <div className="mt-4">
          <WithdrawAccount audience="pending" account={withdrawTarget} />
        </div>
      </div>
    </div>
  );
}
