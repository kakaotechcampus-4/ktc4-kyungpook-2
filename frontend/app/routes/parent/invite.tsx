import { useEffect, useRef, useState } from "react";
import { Busy } from "@/components/ui";
import { Link, redirect, useLoaderData, useNavigate, useRevalidator } from "react-router";
import { KakaoLoginButton } from "@/components/KakaoLoginButton";
import { StepProgress } from "@/components/parent/StepProgress";
import { TermsForm, type TermItem } from "@/components/TermsForm";
import { ApiError, acceptInvitation, restoreParentOnboarded } from "@/lib/api";
import {
  agreeTerms,
  clearInviteCode,
  getSession,
  grantRole,
  isAuthMock,
  readInviteCode,
  saveInviteCode,
  type TermKey,
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
 *  - 카카오에서 막 돌아온 사람(`/oauth/success` 가 보낸다): 약관에 동의하지 않았으면 terms 부터
 *  - 이미 쓰고 있는 사람("아이 추가"나 새 링크로 들어온다): 약관에 동의했으면 code 부터
 *
 * 약관 동의 여부는 서버가 안다(`/auth/me` 의 termsAgreed). 동의를 저장한 뒤에는 받아 둔 코드가 있으면
 * 코드 단계로 이어 가고, 코드 없이 이미 아이가 연결된 보호자(약관 판이 올라 다시 동의한 경우)는 홈으로 간다.
 *
 * code 단계에 보관된 코드가 있으면 바로 수락한다. 링크가 안 열렸거나 sessionStorage 가
 * 사라진 경우(카톡 인앱 브라우저 → 외부 브라우저)를 위해 직접 입력 칸도 둔다.
 */
type Step = "intro" | "terms" | "code" | "done";

// 서버 TermKey 와 같은 값이다. 지금은 모두 필수라 체크한 목록이 아니라 이 목록 전체를 보낸다.
const TERMS: readonly TermItem[] = [
  { key: "tos", label: "서비스 이용약관 동의", required: true },
  { key: "privacy", label: "개인정보와 민감정보 수집 동의", required: true },
];

export async function clientLoader({ request }: { request: Request }) {
  const code = new URL(request.url).searchParams.get("code")?.trim();
  if (code) saveInviteCode(code);

  const { loggedIn, signupCompleted, role, termsAgreed } = await getSession();
  // 카카오 로그인만 하고 가입을 안 끝낸 사람은 가입부터 — 코드 수락이 403 이다.
  if (loggedIn && !signupCompleted) return redirect("/signup?role=parent");
  const isParent = loggedIn && role === "parent";
  return {
    role: loggedIn ? role : null,
    termsAgreed: isParent && termsAgreed,
    // 약관에 동의한 보호자만 묻는다 — 동의 전에는 아이 목록이 403 TERMS_NOT_AGREED 다
    onboarded: isParent && termsAgreed && (await restoreParentOnboarded()),
    savedCode: readInviteCode(),
  };
}

export default function ParentInvitePage() {
  const loaded = useLoaderData<typeof clientLoader>();
  const { role, savedCode } = loaded;
  const [step, setStep] = useState<Step>(() => {
    if (role !== "parent") return "intro";
    return loaded.termsAgreed ? "code" : "terms";
  });
  // 약관에 동의한 뒤 다시 구한다 — 로더는 동의 전이라 묻지 않고 false 를 준다. "이미 연결됨"을 홈으로 보낼지,
  // "홈으로" 링크를 보일지가 이 값을 본다
  const [onboarded, setOnboarded] = useState(loaded.onboarded);
  const [agreed, setAgreed] = useState<TermKey[]>([]);
  const [code, setCode] = useState(savedCode ?? "");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<AcceptedInvitation | "already_linked" | null>(null);
  const navigate = useNavigate();
  const revalidator = useRevalidator();
  const mock = isAuthMock();
  const autoTried = useRef(false);

  async function submitTerms() {
    if (pending) return;
    setError(null);
    setPending(true);
    try {
      await agreeTerms(TERMS.map((t) => t.key));
      const linked = await restoreParentOnboarded();
      setOnboarded(linked);
      // 받아 둔 코드(링크로 받았거나 코드 단계에서 막혀 돌아온 입력)가 있으면 수락을 이어 간다
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
      case "TERMS_NOT_AGREED":
        // 코드 단계를 연 채 약관 판이 올라간 경우 등. 공통 처리는 이미 이 주소라 옮기지 않으므로 여기서 약관으로
        // 돌린다. 보관된 코드는 동의 뒤 다시 자동 수락하도록 시도 표시를 되돌린다.
        autoTried.current = false;
        setAgreed([]);
        setStep("terms");
        return;
    }
    if (err.status === 401) {
      // 로그인이 풀렸다. 코드는 보관돼 있으니 다시 로그인하면 이어서 수락한다.
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

  // 링크로 받아 둔 코드는 코드 단계에 들어오자마자 한 번만 수락한다
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
          {/* 세 곳에서 오던 소식이 아이 하나로 모이는 그림. 선생님 화면과 같은 그림을 쓴다 */}
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
              className="size-[92px] rounded-[26px] bg-surface object-contain p-1 shadow-[0_6px_20px_rgba(120,90,40,0.14)]"
            />
          </div>

          {/* 문구는 로그인 화면(web)과 같은 것을 쓴다 — 슬로건 · 핵심 가치 · 서비스 설명 */}
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
            agreed={agreed}
            onChange={setAgreed}
            pending={pending}
            error={error}
            onSubmit={() => void submitTerms()}
            buttonClassName={primary}
          />
        </>
      ) : null}

      {step === "code" ? (
        <div className="flex flex-col gap-4">
          {/*
            이미 쓰고 있는 보호자가 "아이 추가"로 들어온 경우 — 온보딩 진행 표시 대신
            돌아갈 길을 준다. 이 화면은 셸에서 하단 탭이 숨겨지는 온보딩 경로라
            나가는 문이 따로 없다.
          */}
          {/*
            처음 오는 사람은 약관 · 코드 · 공유 동의 세 단계.
            이미 쓰는 사람이 "아이 추가" 로 들어오면 약관은 지났으니 코드 · 공유 동의 두 단계다.
            전에는 이 경우 단계를 아예 안 보여 줘서 몇 번 더 눌러야 하는지 알 수 없었다.
          */}
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

/** 소식을 보내는 쪽 한 칸 — 그림과 이름 */
function Sender({ src, label }: { src: string; label: string }) {
  return (
    <span className="flex flex-col items-center gap-1">
      <img
        src={src}
        alt=""
        aria-hidden
        width={54}
        height={54}
        className="size-[54px] rounded-[18px] bg-surface object-contain p-0.5 shadow-[0_3px_12px_rgba(120,90,40,0.1)]"
      />
      <span className="text-[12px] font-semibold text-muted">{label}</span>
    </span>
  );
}
