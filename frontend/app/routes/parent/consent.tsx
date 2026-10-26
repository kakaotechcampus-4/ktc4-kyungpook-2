import { useState, useTransition } from "react";
import { Link, redirect, useLoaderData, useNavigate } from "react-router";
import { grantRole, isOnboarded, markOnboarded } from "@/lib/auth";
import { ApiError, getConsentPreview, grantConsent, isReady, unlinkChild } from "@/lib/api";
import { InstitutionChip } from "@/components/ui";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { StepProgress } from "@/components/parent/StepProgress";

const INVITE = "/parent/invite";
/** 온보딩 밖에서 이 화면으로 온 곳. 동의 뒤 그곳으로 돌아간다 */
const RETURN_TO = {
  settings: { path: "/parent/settings", label: "설정으로 돌아가기" },
  notifications: { path: "/parent/notifications", label: "알림으로 돌아가기" },
} as const;
type ReturnFrom = keyof typeof RETURN_TO;

/**
 * 대상은 쿼리로 받는다 — P-01 이 초대 코드를 수락한 뒤 "어느 아이 · 어느 기관"인지 지정해
 * 보낸다. 쿼리가 빠졌으면 무엇에 동의하는지 알 수 없으므로 코드 입력 화면으로 되돌린다.
 *
 * 설정(P-11)에서 꺼 둔 기관을 다시 켤 때(`from=settings`)와 알림에서 새 기관 요청에 권한을 줄 때
 * (`from=notifications`)도 이 화면을 거친다. 공유 항목을 보고 동의해야 해서다. 그때는 동의 뒤 온 곳으로 돌아간다.
 */
export async function clientLoader({ request }: { request: Request }) {
  const url = new URL(request.url);
  const childId = url.searchParams.get("childId");
  const institutionId = url.searchParams.get("institutionId");
  if (!childId || !institutionId) return redirect(INVITE);
  const fromParam = url.searchParams.get("from");
  const from: ReturnFrom | null = fromParam === "settings" || fromParam === "notifications" ? fromParam : null;
  return { preview: await getConsentPreview(childId, institutionId), from };
}

/**
 * P-02 확인 · 동의 — 한 화면에서 아이 확인 + 기관 확인 + 공유 범위 동의를 끝낸다.
 *
 * P-01 에서 초대 코드로 연결한 아이와 그 코드를 준 기관을 보여준다. 찾지 못하면 다른 아이나
 * 기관으로 채우지 않고 "찾을 수 없음" 을 보여준다 — 엉뚱한 아이에 동의하게 만들면 안 된다.
 * 우리 아이가 아니면 연결을 끊는다(G-05). 코드는 본인 확인이 아니라서 잘못 연결될 수 있다.
 */
export default function ParentConsentPage() {
  const { preview, from } = useLoaderData<typeof clientLoader>();
  // 설정·알림에서 왔거나 온보딩을 마친 보호자에게는 온보딩 단계 표시가 맞지 않는다
  const returnTo = from ? RETURN_TO[from] : null;
  const showSteps = !returnTo && !isOnboarded();
  const [consented, setConsented] = useState(false);
  const [mismatch, setMismatch] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const [rejecting, startReject] = useTransition();
  const navigate = useNavigate();

  if (!preview) {
    return (
      <div className="flex flex-col gap-5">
        <div className="rounded-2xl border border-block/40 bg-blocksoft px-4 py-3">
          <p className="mb-1 text-[16px] font-bold text-block">동의할 내용을 찾을 수 없어요</p>
          <p className="text-[15px] leading-7 text-ink2">
            이미 동의했거나 연결이 끊긴 아이예요. 기관에서 받은 코드가 있다면 다시 입력해 주세요.
          </p>
        </div>
        <button
          onClick={() => navigate(returnTo ? returnTo.path : INVITE)}
          className="tap h-14 w-full rounded-2xl border border-line2 px-4 text-[16px] font-bold text-ink2"
        >
          {returnTo ? returnTo.label : "코드 입력 화면으로"}
        </button>
      </div>
    );
  }

  const { child, institution, documentUrl, sharedFields, notSharedFields, allowedFields } =
    preview;

  function save() {
    setSaveError(null);
    start(async () => {
      try {
        await grantConsent(child.id, institution.id, allowedFields);
      } catch (err) {
        if (err instanceof ApiError && err.code === "SIGNUP_NOT_COMPLETED") {
          navigate("/signup?role=parent", { replace: true });
          return;
        }
        setSaveError(saveErrorMessage(err));
        return;
      }
      // 설정·알림에서 온 것이면 그곳으로 돌아간다. 온보딩 중이면 돌봄 정보 입력(4단계)까지
      // 이어가고, 이미 쓰던 보호자가 새 기관 요청을 승인한 것이면 홈으로 돌아간다.
      if (returnTo) {
        navigate(returnTo.path);
      } else if (isOnboarded()) {
        navigate("/parent");
      } else {
        grantRole("parent"); // 쿼리로 바로 들어온 경우를 위한 보정
        markOnboarded();
        // 돌봄 정보는 서버에 저장할 곳이 생기기 전까지 건너뛴다 — "준비 중" 화면에서 온보딩을 끝내지 않게
        navigate(isReady("careInfo") ? "/parent/care-info?onboarding=1" : "/parent");
      }
    });
  }

  if (mismatch) {
    return (
      <div className="flex flex-col gap-5">
        <div className="rounded-2xl border border-block/40 bg-blocksoft px-4 py-3">
          <p className="mb-1 text-[16px] font-bold text-block">
            아이 정보가 일치하지 않아요
          </p>
          <p className="text-[15px] leading-7 text-ink2">
            화면에 표시된 이름과 생년월일이 우리 아이와 다르다면, 연결을 끊고 기관에
            알려주세요.
          </p>
        </div>
        <div className="rounded-2xl border border-line bg-paper px-4 py-3">
          <p className="mb-1 text-[13px] font-semibold tracking-wider text-muted uppercase">
            기관이 등록한 정보
          </p>
          <p className="text-[16px] font-semibold">
            {child.name} · {child.birthDate.replaceAll("-", ".")}생
          </p>
        </div>
        <button
          onClick={() =>
            startReject(async () => {
              await unlinkChild(child.id);
              navigate(INVITE);
            })
          }
          disabled={rejecting}
          className="tap h-14 w-full rounded-2xl bg-block px-4 text-[16px] font-bold text-white disabled:opacity-60"
        >
          {rejecting ? "처리 중…" : "정보가 달라요 · 연결 끊기"}
        </button>
        <button
          onClick={() => setMismatch(false)}
          className="tap h-14 w-full rounded-2xl border border-line2 px-4 text-[16px] font-bold text-ink2"
        >
          우리 아이가 맞아요
        </button>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-5 pb-24">
      {showSteps ? <StepProgress step={3} total={4} /> : null}

      <p className="rounded-2xl bg-accentsoft px-4 py-3 text-[15px] leading-6 text-accentink">
        <b className="font-semibold">{institution.name}</b>에서 {child.name} 학생을
        등록했어요
      </p>

      <section>
        <h2 className="mb-2 text-[16px] font-bold">우리 아이 정보</h2>
        <div className="flex items-center gap-3 rounded-2xl border border-line px-4 py-3">
          <span
            aria-hidden
            className="flex size-10 items-center justify-center rounded-full bg-surface2 text-[16px] font-semibold text-ink2"
          >
            {child.name.slice(0, 1)}
          </span>
          <span className="text-[16px] font-semibold">
            {child.name} · {child.birthDate.replaceAll("-", ".")}생
          </span>
        </div>
        <button
          onClick={() => setMismatch(true)}
          className="mt-2 text-[14px] text-muted underline"
        >
          우리 아이가 아니에요
        </button>
      </section>

      <section>
        <h2 className="mb-2 text-[16px] font-bold">이 기관은 어디인가요</h2>
        <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-line px-4 py-3">
          <InstitutionIcon type={institution.type} />
          <InstitutionChip institution={institution} withName />
          {documentUrl ? (
            <a
              href={documentUrl}
              target="_blank"
              rel="noreferrer"
              className="ml-auto text-[14px] text-accentink underline"
            >
              증빙서류 보기 ›
            </a>
          ) : null}
        </div>
      </section>

      <section className="rounded-2xl bg-surface2 px-4 py-4">
        <p className="mb-2 text-[13px] font-bold text-accentink">공유되는 정보</p>
        <ul className="flex flex-col gap-2">
          {sharedFields.map((label) => (
            <li key={label} className="text-[15px]">
              {label}
            </li>
          ))}
        </ul>
        <p className="mt-3 text-[14px] leading-6 text-muted">
          {notSharedFields.join(", ")}는 공유되지 않습니다.
        </p>
      </section>

      <label className="tap flex cursor-pointer items-start gap-3 rounded-2xl border border-line px-4 py-3 has-checked:border-accent has-checked:bg-accentsoft">
        <input
          type="checkbox"
          checked={consented}
          onChange={() => setConsented((c) => !c)}
          className="mt-1 size-5 shrink-0 accent-accent"
        />
        <span className="text-[15px] leading-6">
          위 정보가 <b className="font-semibold">{institution.name}</b>에 공유되는 것에
          동의합니다. 언제든 [동의 관리]에서 회수할 수 있어요.
        </span>
      </label>

      {/* 하단 고정 — 스크롤 위치와 무관하게 동의 버튼이 보여야 한다 */}
      <div className="fixed bottom-0 left-1/2 w-full max-w-[430px] md:max-w-[560px] lg:max-w-[640px] -translate-x-1/2 border-t border-line bg-surface px-4 py-3">
        {saveError ? (
          <p role="alert" className="mb-2 text-center text-[14px] leading-6 text-block">
            {saveError}
          </p>
        ) : null}
        <button
          onClick={save}
          disabled={!consented || pending}
          className="tap h-14 w-full rounded-2xl bg-accent px-4 text-[16px] font-bold text-white hover:bg-accentink disabled:bg-surface2 disabled:text-muted"
        >
          {pending ? "확인 중…" : "동의하고 시작하기"}
        </button>
        {!consented ? (
          <p className="mt-1.5 text-center text-[14px] text-muted">
            동의해야 다음으로 넘어갈 수 있어요.
          </p>
        ) : null}
      </div>
    </div>
  );
}

/** 동의 저장 실패를 보호자가 할 수 있는 일로 바꿔 말한다 (api-spec G-41 오류) */
function saveErrorMessage(err: unknown): string {
  if (!(err instanceof ApiError)) return "네트워크 연결을 확인하고 다시 시도해 주세요.";
  if (err.code === "CONSENT_TARGET_NOT_FOUND") {
    return "기관 명부에서 아이 정보를 찾을 수 없어요. 기관에 확인해 주세요.";
  }
  if (err.code === "PARENT_REQUIRED") {
    return "기관 계정으로 로그인돼 있어요. 보호자 카카오 계정으로 다시 로그인해 주세요.";
  }
  if (err.status === 401) return "로그인이 풀렸어요. 다시 로그인한 뒤 시도해 주세요.";
  // 연결이 끊긴 아이이거나 CSRF 토큰 문제다. 둘 다 새로고침하면 미리보기부터 다시 확인한다.
  if (err.status === 403) return "보안 확인에 실패했습니다. 새로고침한 뒤 다시 시도해 주세요.";
  return "일시적인 오류로 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.";
}

/**
 * 미리보기(G-02)를 불러오지 못했을 때. 404(동의할 것이 없음)는 loader 가 null 로 바꿔
 * 화면에서 안내하므로, 여기에는 401 · 403 · 서버 오류 · 네트워크 오류만 온다.
 */
export function ErrorBoundary({ error }: { error: unknown }) {
  const forbidden = error instanceof ApiError && error.status === 403;
  const unauthorized = error instanceof ApiError && error.status === 401;
  return (
    <div className="flex flex-col gap-5">
      <div className="rounded-2xl border border-block/40 bg-blocksoft px-4 py-3">
        <p className="mb-1 text-[16px] font-bold text-block">동의 화면을 불러오지 못했어요</p>
        <p className="text-[15px] leading-7 text-ink2">
          {unauthorized
            ? "로그인이 풀렸어요. 다시 로그인한 뒤 기관에서 받은 코드를 입력해 주세요."
            : forbidden
              ? "보호자님 계정과 연결된 아이가 아니에요. 기관에서 받은 코드를 다시 입력해 주세요."
              : "잠시 뒤 다시 시도해 주세요."}
        </p>
      </div>
      <Link
        to={INVITE}
        className="tap flex h-14 w-full items-center justify-center rounded-2xl border border-line2 px-4 text-[16px] font-bold text-ink2"
      >
        코드 입력 화면으로
      </Link>
    </div>
  );
}
