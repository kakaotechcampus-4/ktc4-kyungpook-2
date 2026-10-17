import { useEffect, useRef, useState } from "react";
import { Link, redirect, useLoaderData, useNavigate, useRevalidator } from "react-router";
import { KakaoLoginButton } from "@/components/KakaoLoginButton";
import { StepProgress } from "@/components/parent/StepProgress";
import { ApiError, acceptInvitation } from "@/lib/api";
import {
  clearInviteCode,
  getSession,
  grantRole,
  isAuthMock,
  isOnboarded,
  readInviteCode,
  saveInviteCode,
} from "@/lib/auth";
import type { AcceptedInvitation } from "@/lib/types";

/**
 * P-01 보호자 진입 — 카카오 로그인 · 약관 동의 · 초대 코드 수락 (docs/api/api-spec.md §5.1).
 *
 * 기관이 아이를 등록하면 10분짜리 일회용 초대 코드가 나오고, 기관이 코드나 링크
 * (`/parent/invite?code=K7P3QX9M`)를 카톡 등으로 보낸다. 링크의 코드는 sessionStorage 에
 * 보관해서 카카오 로그인 · 회원가입을 다녀와도 남게 한다 (lib/auth.ts saveInviteCode).
 *
 *  - 처음 오는 사람: intro → terms → code → done
 *  - 카카오에서 막 돌아온 사람(`/oauth/success` 가 보낸다): terms 부터
 *  - 이미 쓰고 있는 사람("아이 추가"나 새 링크로 들어온다): code 부터
 *
 * code 단계에 보관된 코드가 있으면 바로 수락한다. 링크가 안 열렸거나 sessionStorage 가
 * 사라진 경우(카톡 인앱 브라우저 → 외부 브라우저)를 위해 직접 입력 칸도 둔다.
 */
type Step = "intro" | "terms" | "code" | "done";

const TERMS = [
  { key: "tos", label: "서비스 이용약관 동의", required: true },
  { key: "privacy", label: "개인정보와 민감정보 수집 동의", required: true },
  { key: "notify", label: "새로운 소식 알림 받기", required: false },
] as const;

export async function clientLoader({ request }: { request: Request }) {
  const code = new URL(request.url).searchParams.get("code")?.trim();
  if (code) saveInviteCode(code);

  const { loggedIn, signupCompleted, role } = await getSession();
  // 카카오 로그인만 하고 가입을 안 끝낸 사람은 가입부터 — 코드 수락이 403 이다.
  if (loggedIn && !signupCompleted) return redirect("/signup?role=parent");
  return {
    role: loggedIn ? role : null,
    onboarded: role === "parent" && isOnboarded(),
    savedCode: readInviteCode(),
  };
}

export default function ParentInvitePage() {
  const { role, onboarded, savedCode } = useLoaderData<typeof clientLoader>();
  const [step, setStep] = useState<Step>(() => {
    if (role !== "parent") return "intro";
    return onboarded ? "code" : "terms";
  });
  const [agreed, setAgreed] = useState<string[]>([]);
  const [code, setCode] = useState(savedCode ?? "");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<AcceptedInvitation | "already_linked" | null>(null);
  const navigate = useNavigate();
  const revalidator = useRevalidator();
  const mock = isAuthMock();
  const autoTried = useRef(false);

  async function accept(input: string) {
    if (!input.trim() || pending) return;
    setError(null);
    setPending(true);
    try {
      const accepted = await acceptInvitation(input.trim());
      clearInviteCode();
      setResult(accepted);
      setStep("done");
    } catch (err) {
      handleError(err);
    } finally {
      setPending(false);
    }
  }

  function handleError(err: unknown) {
    if (!(err instanceof ApiError)) {
      setError("네트워크 연결을 확인하고 다시 시도해 주세요.");
      return;
    }
    switch (err.code) {
      case "LINK_ALREADY_EXISTS":
        // 이미 연결된 아이다 — 실패가 아니라 다음 단계로 넘어간다
        clearInviteCode();
        if (onboarded) {
          navigate("/parent");
          return;
        }
        setResult("already_linked");
        setStep("done");
        return;
      case "INVALID_INVITE_CODE":
        // 보관된 코드가 틀렸으면 다음 로그인 때 또 자동 수락하지 않게 지운다
        clearInviteCode();
        setError("코드가 올바르지 않거나 만료됐어요. 기관에 새 코드를 요청하세요.");
        return;
      case "INVALID_REQUEST":
        setError("기관에서 받은 코드를 입력해 주세요.");
        return;
      case "PARENT_REQUIRED":
        setError("기관 계정으로 로그인돼 있어요. 보호자 카카오 계정으로 다시 로그인해 주세요.");
        return;
      case "SIGNUP_NOT_COMPLETED":
        navigate("/signup?role=parent", { replace: true });
        return;
    }
    if (err.status === 401) {
      // 로그인이 풀렸다. 코드는 보관돼 있으니 다시 로그인하면 이어서 수락한다.
      setStep("intro");
      revalidator.revalidate();
      return;
    }
    if (err.status === 403) {
      setError("보안 확인에 실패했습니다. 새로고침한 뒤 다시 시도해 주세요.");
      return;
    }
    setError("잠시 후 다시 시도해 주세요.");
  }

  // 링크로 받아 둔 코드는 코드 단계에 들어오자마자 한 번만 수락한다
  useEffect(() => {
    if (step !== "code" || !savedCode || autoTried.current) return;
    autoTried.current = true;
    void accept(savedCode);
  }, [step, savedCode]);

  const primary =
    "tap h-14 w-full rounded-2xl bg-accent px-4 text-[16px] font-bold text-white hover:bg-accentink disabled:bg-surface2 disabled:text-muted";
  const secondary =
    "tap flex h-14 w-full items-center justify-center rounded-2xl border border-line2 px-4 text-[16px] font-bold text-ink2 hover:bg-surface2";
  const requiredMissing = TERMS.some((t) => t.required && !agreed.includes(t.key));

  if (role === "org") {
    return (
      <div className="flex flex-col gap-5">
        <div className="rounded-2xl border border-block/40 bg-blocksoft px-4 py-3">
          <p className="mb-1 text-[16px] font-bold text-block">기관 계정으로 로그인돼 있어요</p>
          <p className="text-[15px] leading-7 text-ink2">
            초대 코드는 보호자 계정으로만 쓸 수 있어요. 보호자에게 코드나 링크를 보내 주세요.
          </p>
        </div>
        <Link to="/dashboard" className={secondary}>
          기관 화면으로
        </Link>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      {step === "intro" ? (
        <div className="flex flex-col gap-6">
          <div>
            <h1 className="mb-2 text-[26px] leading-[1.3] font-extrabold tracking-tight">
              흩어진 돌봄 기록을
              <br />한 곳에서 봅니다
            </h1>
            <p className="text-[15px] leading-7 text-ink2">
              학교와 센터, 학원에서 따로 오던 소식을 아이별로 모읍니다. 정보는 보호자가 허락한
              기관에만 공유됩니다.
            </p>
          </div>

          <div className="flex flex-col gap-3">
            <KakaoLoginButton
              intent="parent"
              label="카카오로 시작하기"
              className="h-14 rounded-2xl"
            />
            <p className="text-[13px] leading-6 text-muted">
              {savedCode
                ? "기관에서 보낸 초대 코드를 받아 두었어요. 로그인하면 아이와 바로 연결됩니다."
                : "로그인한 뒤 기관에서 받은 초대 코드를 입력하면 아이와 연결됩니다."}
            </p>
          </div>

          {/*
            데모용 진입. 카카오 키 없이도 학부모 화면 전체를 볼 수 있어야 한다 —
            기관 로그인(/login)에 둔 것과 같은 장치다.
            실연동 빌드(VITE_AUTH_MOCK=false)에서는 렌더링되지 않는다.
          */}
          {mock ? (
            <div className="flex flex-col gap-2">
              <div className="flex items-center gap-3 text-[13px] text-muted">
                <span className="h-px flex-1 bg-line" />
                데모
                <span className="h-px flex-1 bg-line" />
              </div>
              <button
                type="button"
                onClick={() => {
                  grantRole("parent");
                  revalidator.revalidate();
                  setStep(isOnboarded() ? "code" : "terms");
                }}
                className="tap h-14 w-full rounded-2xl border border-line2 px-4 text-[16px] font-bold text-ink2 hover:bg-surface2"
              >
                mock 데이터로 둘러보기
              </button>
            </div>
          ) : null}
        </div>
      ) : null}

      {step === "terms" ? (
        <>
          <div className="flex flex-col gap-4">
            <StepProgress step={1} total={4} />
            <div>
              <h1 className="mb-1.5 text-[20px] font-extrabold tracking-tight">
                서비스 이용에 동의해 주세요
              </h1>
              <p className="text-[15px] leading-7 text-ink2">
                카카오 로그인 동의와는 별개로, 잇다가 아이 정보를 다루는 방식에 대한 동의입니다.
              </p>
            </div>
          </div>

          <form
            onSubmit={(e) => {
              e.preventDefault();
              setStep("code");
            }}
            className="flex flex-col gap-3"
          >
            <ul className="flex flex-col gap-2">
              {TERMS.map((t) => {
                const on = agreed.includes(t.key);
                return (
                  <li key={t.key}>
                    <label className="tap flex cursor-pointer items-center gap-3 rounded-2xl border border-line2 px-4 has-checked:border-accent has-checked:bg-accentsoft">
                      <input
                        type="checkbox"
                        checked={on}
                        onChange={() =>
                          setAgreed((a) => (on ? a.filter((k) => k !== t.key) : [...a, t.key]))
                        }
                        className="size-5 shrink-0 accent-accent"
                      />
                      <span className="flex flex-1 items-center gap-2 text-[15px]">
                        {t.label}
                        <span
                          className={`rounded-full px-2 py-0.5 text-[12px] font-bold ${
                            t.required ? "bg-accentsoft text-accentink" : "bg-surface2 text-muted"
                          }`}
                        >
                          {t.required ? "필수" : "선택"}
                        </span>
                      </span>
                    </label>
                  </li>
                );
              })}
            </ul>
            <button disabled={requiredMissing} className={primary}>
              동의하고 계속하기
            </button>
            {requiredMissing ? (
              <p className="text-center text-[14px] text-muted">필수 동의를 선택해주세요</p>
            ) : null}
          </form>
        </>
      ) : null}

      {step === "code" ? (
        <div className="flex flex-col gap-4">
          {/*
            이미 쓰고 있는 보호자가 "아이 추가"로 들어온 경우 — 온보딩 진행 표시 대신
            돌아갈 길을 준다. 이 화면은 셸에서 하단 탭이 숨겨지는 온보딩 경로라
            나가는 문이 따로 없다.
          */}
          {onboarded ? (
            <Link to="/parent" className="text-[14px] font-semibold text-accentink">
              ‹ 홈으로
            </Link>
          ) : (
            <StepProgress step={2} total={4} />
          )}
          <div>
            <h1 className="mb-1.5 text-[20px] font-extrabold tracking-tight">
              초대 코드를 입력해 주세요
            </h1>
            <p className="text-[15px] leading-7 text-ink2">
              기관에서 카톡 등으로 받은 8자리 코드입니다. 코드는 발급 후 10분 동안 한 번만 쓸 수
              있어요.
            </p>
          </div>

          <form
            onSubmit={(e) => {
              e.preventDefault();
              void accept(code);
            }}
            className="flex flex-col gap-3"
          >
            <input
              value={code}
              onChange={(e) => {
                setCode(e.target.value);
                if (error) setError(null);
              }}
              maxLength={20}
              autoCapitalize="characters"
              autoComplete="one-time-code"
              placeholder="K7P3-QX9M"
              aria-label="초대 코드"
              aria-invalid={error ? true : undefined}
              className="tap h-14 rounded-2xl border border-line2 px-4 text-center font-mono text-[20px] tracking-[0.2em] uppercase outline-none focus:border-accent aria-invalid:border-block"
            />
            {error ? (
              <p role="alert" className="rounded-2xl bg-blocksoft px-4 py-3 text-[14px] leading-6 text-block">
                {error}
              </p>
            ) : null}
            <button disabled={!code.trim() || pending} className={primary}>
              {pending ? "확인 중…" : "아이와 연결하기"}
            </button>
          </form>
          <p className="text-[13px] leading-6 text-muted">
            코드가 만료됐다면 기관에 새 코드를 요청하세요. 하이픈과 대소문자는 신경 쓰지 않아도
            됩니다.
          </p>
        </div>
      ) : null}

      {step === "done" && result ? (
        <AcceptedStep result={result} onboarded={onboarded} />
      ) : null}
    </div>
  );
}

/**
 * 수락 결과. 연결은 동의가 아니다 — 수락은 아동 상태를 바꾸지 않아서, 동의가 남았으면
 * 동의 화면(P-02)으로 이어 준다.
 */
function AcceptedStep({
  result,
  onboarded,
}: {
  result: AcceptedInvitation | "already_linked";
  onboarded: boolean;
}) {
  const primary =
    "tap flex h-14 w-full items-center justify-center rounded-2xl bg-accent px-4 text-[16px] font-bold text-white hover:bg-accentink";
  const home = onboarded ? (
    <Link
      to="/parent"
      className="tap flex h-14 w-full items-center justify-center rounded-2xl border border-line2 px-4 text-[16px] font-bold text-ink2"
    >
      홈으로
    </Link>
  ) : null;

  if (result === "already_linked") {
    return (
      <div className="flex flex-col gap-4">
        <div className="rounded-2xl bg-accentsoft px-4 py-4">
          <p className="mb-1 text-[16px] font-bold text-accentink">이미 연결된 아이예요</p>
          <p className="text-[15px] leading-7 text-ink2">
            이 코드의 아이는 이미 보호자님 계정과 연결돼 있어요. 코드는 쓰이지 않았습니다.
          </p>
        </div>
        {home}
      </div>
    );
  }

  const needsConsent = result.childStatus === "pending_consent";
  const consentPath = `/parent/consent?childId=${encodeURIComponent(result.childId)}&institutionId=${encodeURIComponent(result.organizationId)}`;

  return (
    <div className="flex flex-col gap-4">
      {onboarded ? null : <StepProgress step={2} total={4} />}
      <div className="rounded-2xl bg-accentsoft px-4 py-4">
        <p className="mb-1 text-[16px] font-bold text-accentink">
          {result.childName} 학생과 연결됐어요
        </p>
        <p className="text-[15px] leading-7 text-ink2">
          <b className="font-semibold">{result.organizationName}</b>에서 보낸 코드로 연결했어요.
          {needsConsent ? (
            <>
              {" "}
              아직 <b className="font-semibold">공유 동의</b>가 남았어요. 동의해야 기관이 아이
              기록을 올릴 수 있어요.
            </>
          ) : null}
        </p>
      </div>
      <Link to={consentPath} className={primary}>
        {needsConsent ? "공유 동의하러 가기" : "공유 범위 확인하기"}
      </Link>
      {home}
    </div>
  );
}
