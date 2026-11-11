import { useEffect, useRef, useState } from "react";
import { Busy } from "@/components/ui";
import { Link, redirect, useLoaderData, useNavigate, useRevalidator } from "react-router";
import { KakaoLoginButton } from "@/components/KakaoLoginButton";
import { WithdrawAccount } from "@/components/WithdrawAccount";
import { StepProgress } from "@/components/parent/StepProgress";
import { TERMS_CHANGED_MESSAGE, TermsForm, useTermsDocuments, type TermItem } from "@/components/TermsForm";
import { ApiError, acceptInvitation, restoreParentOnboarded } from "@/lib/api";
import {
  agreeTerms,
  clearInviteCode,
  getSession,
  grantRole,
  hasReturnPath,
  isAuthMock,
  readInviteCode,
  saveInviteCode,
  withdrawTargetOf,
  type TermKey,
} from "@/lib/auth";
import type { AcceptedInvitation } from "@/lib/types";

type Step = "intro" | "terms" | "code" | "done";

const TERMS: readonly TermItem[] = [
  { key: "tos", label: "서비스 이용약관 동의", required: true },
  { key: "privacy", label: "개인정보와 민감정보 수집 동의", required: true },
];

export async function clientLoader({ request }: { request: Request }) {
  const code = new URL(request.url).searchParams.get("code")?.trim();
  if (code) saveInviteCode(code);

  const session = await getSession();
  const { loggedIn, signupCompleted, role, termsAgreed, withdrawing } = session;
  if (withdrawing) return redirect("/withdrawal");
  if (loggedIn && !signupCompleted) return redirect("/signup?role=parent");
  const isParent = loggedIn && role === "parent";
  return {
    role: loggedIn ? role : null,
    termsAgreed: isParent && termsAgreed,
    onboarded: isParent && termsAgreed && (await restoreParentOnboarded()),
    savedCode: readInviteCode(),
    withdrawTarget: isParent ? withdrawTargetOf(session) : null,
  };
}

export default function ParentInvitePage() {
  const loaded = useLoaderData<typeof clientLoader>();
  const { role, savedCode } = loaded;
  const [step, setStep] = useState<Step>(() => {
    if (role !== "parent") return "intro";
    return loaded.termsAgreed ? "code" : "terms";
  });
  const [onboarded, setOnboarded] = useState(loaded.onboarded);
  const [agreed, setAgreed] = useState<TermKey[]>([]);
  const terms = useTermsDocuments("parent", TERMS, step === "terms");
  const [code, setCode] = useState(savedCode ?? "");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<AcceptedInvitation | "already_linked" | null>(null);
  const navigate = useNavigate();
  const revalidator = useRevalidator();
  const mock = isAuthMock();
  const autoTried = useRef(false);
  const [expired, setExpired] = useState(false);
  useEffect(() => setExpired(hasReturnPath()), []);

  async function submitTerms() {
    const picked = terms.versionsOf(agreed);
    if (pending || !picked) return;
    setError(null);
    setPending(true);
    try {
      await agreeTerms(picked);
      const linked = await restoreParentOnboarded();
      setOnboarded(linked);
      if (linked && !readInviteCode() && !code.trim()) {
        navigate("/parent", { replace: true });
        return;
      }
      setStep("code");
    } catch (err) {
      if (err instanceof ApiError && (err.status === 401 || err.code === "SIGNUP_NOT_COMPLETED")) {
        handleError(err);
        return;
      }
      if (err instanceof ApiError && err.code === "TERMS_VERSION_CHANGED") {
        const changed = await terms.reloadChanged();
        setAgreed((prev) => prev.filter((k) => !changed.includes(k)));
        setError(TERMS_CHANGED_MESSAGE);
        return;
      }
      setError("동의를 저장하지 못했어요. 다시 시도해 주세요.");
    } finally {
      setPending(false);
    }
  }

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
        clearInviteCode();
        if (onboarded) {
          navigate("/parent");
          return;
        }
        setResult("already_linked");
        setStep("done");
        return;
      case "INVALID_INVITE_CODE":
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
      case "TERMS_NOT_AGREED":
        autoTried.current = false;
        setAgreed([]);
        setStep("terms");
        return;
    }
    if (err.status === 401) {
      setStep("intro");
      revalidator.revalidate();
      return;
    }
    if (err.status === 403) {
      setError("보안 확인에 실패했어요. 새로고침한 뒤 다시 시도해 주세요.");
      return;
    }
    setError("잠시 후 다시 시도해 주세요.");
  }

  useEffect(() => {
    if (step !== "code" || !savedCode || autoTried.current) return;
    autoTried.current = true;
    void accept(savedCode);
  }, [step, savedCode]);

  const primary =
    "tap h-14 w-full rounded-2xl bg-ink px-4 text-[16px] font-bold text-paper hover:brightness-110 disabled:bg-surface2 disabled:text-muted";
  const secondary =
    "tap flex h-14 w-full items-center justify-center rounded-2xl border border-line2 px-4 text-[16px] font-bold text-ink2 hover:bg-surface2";

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
          <div className="flex flex-col items-center gap-1 pt-2">
            <div className="flex items-end justify-center gap-5">
              <Sender src="/img/school.png" label="학교" />
              <Sender src="/img/center.png" label="센터" />
              <Sender src="/img/adult-01.png" label="활동지원사" />
            </div>
            <svg aria-hidden viewBox="0 0 160 34" className="h-8 w-40 text-line2">
              <path
                d="M18 2 C18 20 80 14 80 32 M80 2 L80 32 M142 2 C142 20 80 14 80 32"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
              />
            </svg>
            <img
              src="/img/kid-04.png"
              alt=""
              aria-hidden
              width={92}
              height={92}
              className="size-[92px] object-contain drop-shadow-[0_6px_16px_rgba(120,90,40,0.2)]"
            />
          </div>

          <div className="flex flex-col items-center gap-3 text-center">
            <h1 className="text-[30px] leading-[1.25] font-extrabold tracking-[-0.03em] text-ink">
              아이의 세상을 <b className="text-accentink">잇다</b>
            </h1>

            <p className="flex items-center gap-2 text-[14px] font-semibold text-ink2">
              <i aria-hidden className="size-1.5 rounded-full bg-accent" />
              끊김 없는 돌봄, 이어지는 성장
              <i aria-hidden className="size-1.5 rounded-full bg-[#ef8c3b]" />
            </p>

            <p className="text-[15px] leading-7 text-ink2">
              아이와 기관의 정보를 연결해
              <br />더 나은 돌봄과 더 넓은 가능성을 만듭니다.
            </p>
          </div>

          <div className="flex flex-col gap-3">
            {expired ? (
              <p
                role="status"
                className="rounded-2xl border border-line2 bg-surface px-4 py-3 text-center text-[14px] leading-6 text-ink"
              >
                로그인이 만료됐어요. 다시 로그인하면 보던 화면으로 돌아가요
              </p>
            ) : null}
            <KakaoLoginButton
              intent="parent"
              label="카카오로 시작하기"
              className="h-14 rounded-2xl"
            />
            <p className="text-center text-[13px] leading-6 text-muted">
              {savedCode
                ? "기관에서 보낸 초대 코드를 받아 두었어요. 로그인하면 아이와 바로 연결돼요."
                : "로그인한 뒤 기관에서 받은 초대 코드를 입력하면 아이와 연결돼요."}
            </p>
          </div>

          {mock ? (
            <div className="flex flex-col gap-2">
              <div className="flex items-center gap-3 text-[13px] text-muted">
                <span className="h-px flex-1 bg-line" />
                데모
                <span className="h-px flex-1 bg-line" />
              </div>
              <button
                type="button"
                onClick={async () => {
                  grantRole("parent");
                  revalidator.revalidate();
                  const session = await getSession();
                  setOnboarded(session.termsAgreed && (await restoreParentOnboarded()));
                  setStep(session.termsAgreed ? "code" : "terms");
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
            <StepProgress step={1} total={3} />
            <div>
              <h1 className="mb-1.5 text-[20px] font-extrabold tracking-tight">
                서비스 이용에 동의해 주세요
              </h1>
              <p className="text-[15px] leading-7 text-ink2">
                카카오 로그인 동의와는 별개로, 잇다가 아이 정보를 다루는 방식에 대한 동의예요.
              </p>
            </div>
          </div>

          <TermsForm
            terms={TERMS}
            docs={terms.docs}
            onRetry={() => void terms.retry()}
            agreed={agreed}
            onChange={setAgreed}
            pending={pending}
            error={error}
            onSubmit={() => void submitTerms()}
            buttonClassName={primary}
          />
          <WithdrawAccount audience="parent" account={loaded.withdrawTarget} />
        </>
      ) : null}

      {step === "code" ? (
        <div className="flex flex-col gap-4">
          {onboarded ? (
            <div className="flex flex-col gap-3">
              <Link to="/parent" className="text-[14px] font-semibold text-accentink">
                ‹ 홈으로
              </Link>
              <StepProgress step={1} total={2} />
            </div>
          ) : (
            <StepProgress step={2} total={3} />
          )}
          <div>
            <h1 className="mb-1.5 text-[20px] font-extrabold tracking-tight">
              초대 코드를 입력해 주세요
            </h1>
            <p className="text-[15px] leading-7 text-ink2">
              기관에서 카톡 등으로 받은 8자리 코드예요. 코드는 발급 후 10분 동안 한 번만 쓸 수
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
              {pending ? <Busy>확인 중…</Busy> : "아이와 연결하기"}
            </button>
          </form>
          <p className="text-[13px] leading-6 text-muted">
            코드가 만료됐다면 기관에 새 코드를 요청하세요. 하이픈과 대소문자는 신경 쓰지 않아도
            됩니다.
          </p>
          <WithdrawAccount audience="parent" account={loaded.withdrawTarget} />
        </div>
      ) : null}

      {step === "done" && result ? (
        <AcceptedStep result={result} onboarded={onboarded} />
      ) : null}
    </div>
  );
}

function AcceptedStep({
  result,
  onboarded,
}: {
  result: AcceptedInvitation | "already_linked";
  onboarded: boolean;
}) {
  const primary =
    "tap flex h-14 w-full items-center justify-center rounded-2xl bg-ink px-4 text-[16px] font-bold text-paper hover:brightness-110";
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
            이 코드의 아이는 이미 보호자님 계정과 연결돼 있어요. 코드는 쓰이지 않았어요.
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
      <StepProgress step={onboarded ? 1 : 2} total={onboarded ? 2 : 3} />
      <div className="rounded-2xl bg-accentsoft px-4 py-4">
        <p className="mb-1 text-[16px] font-bold text-accentink">
          {result.childName} 학생과 연결됐어요
        </p>
        <p className="text-[15px] leading-7 text-ink2">
          <b className="font-semibold">{result.organizationName}</b>에서 보낸 코드로 연결했어요.
          {needsConsent ? (
            <>
              {" "}
              아직 <b className="font-semibold">공유 동의</b>가 남았어요. 동의해야 기관 기록이{" "}
              {result.childName}에게 이어져요.
            </>
          ) : null}
        </p>
      </div>
      <Link to={consentPath} className={primary}>
        {needsConsent ? "공유 동의하러 가기" : "공유 내용 확인하기"}
      </Link>
      {home}
    </div>
  );
}

function Sender({ src, label }: { src: string; label: string }) {
  return (
    <span className="flex flex-col items-center gap-1">
      <img
        src={src}
        alt=""
        aria-hidden
        width={54}
        height={54}
        className="size-[54px] object-contain drop-shadow-[0_3px_10px_rgba(120,90,40,0.16)]"
      />
      <span className="text-[12px] font-semibold text-muted">{label}</span>
    </span>
  );
}
