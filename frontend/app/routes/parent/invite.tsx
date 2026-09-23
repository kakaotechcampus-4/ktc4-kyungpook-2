import { useState, useTransition } from "react";
import { Link, useLoaderData, useNavigate, useRevalidator } from "react-router";
import { KakaoLoginButton } from "@/components/KakaoLoginButton";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { StepProgress } from "@/components/parent/StepProgress";
import { InstitutionChip } from "@/components/ui";
import { getPendingLinks } from "@/lib/api";
import { getSession, grantRole, isAuthMock, isOnboarded } from "@/lib/auth";
import type { PendingLink } from "@/lib/types";

/**
 * P-01 보호자 진입 — 카카오 로그인 · 약관 동의 · 연결 요청 확인.
 *
 * 초대코드와 SMS 인증 단계는 없앴다. 기관이 아이를 등록하면 서버가 **연결 요청**을
 * 만들어 두고, 보호자는 카카오로 로그인해서 그 요청을 확인하는 방식이다
 * (docs/api/api-spec.md §5.1). 인증은 기관과 같은 카카오 하나로 통일했다.
 *
 * 한 화면에서 세 가지 진입을 다 받는다 —
 *  - 처음 오는 사람: intro → terms → links
 *  - 카카오에서 막 돌아온 사람(`/oauth/success` 가 보낸다): terms 부터
 *  - 이미 쓰고 있는 사람("아이 추가"로 들어온다): links 만
 */
type Step = "intro" | "terms" | "links";

const TERMS = [
  { key: "tos", label: "서비스 이용약관 동의", required: true },
  { key: "privacy", label: "개인정보와 민감정보 수집 동의", required: true },
  { key: "notify", label: "새로운 소식 알림 받기", required: false },
] as const;

export async function clientLoader() {
  const { role } = await getSession();
  // 로그인 전에는 대기 목록을 물어볼 수 없다 — 서버가 401 을 준다.
  if (role !== "parent") {
    return { loggedIn: false, onboarded: false, links: [] as PendingLink[] };
  }
  return { loggedIn: true, onboarded: isOnboarded(), links: await getPendingLinks() };
}

export default function ParentInvitePage() {
  const { loggedIn, onboarded, links } = useLoaderData<typeof clientLoader>();
  const [step, setStep] = useState<Step>(loggedIn ? (onboarded ? "links" : "terms") : "intro");
  const [agreed, setAgreed] = useState<string[]>([]);
  const [pending, start] = useTransition();
  const navigate = useNavigate();
  const revalidator = useRevalidator();
  const mock = isAuthMock();

  const primary =
    "tap h-14 w-full rounded-2xl bg-accent px-4 text-[16px] font-bold text-white hover:bg-accentink disabled:bg-surface2 disabled:text-muted";
  const requiredMissing = TERMS.some((t) => t.required && !agreed.includes(t.key));

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
              기관이 아이를 등록해 두면 로그인한 뒤 연결 요청으로 나타납니다. 따로 받아야 할
              초대코드는 없습니다.
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
                  setStep("terms");
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
            <StepProgress step={2} total={4} />
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
              setStep("links");
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

      {step === "links" ? (
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
              연결 요청을 확인해 주세요
            </h1>
            <p className="text-[15px] leading-7 text-ink2">
              기관이 등록한 아이입니다. 눌러서 아이 정보와 공유 범위를 확인하고 동의 여부를
              정해주세요.
            </p>
          </div>

          {links.length === 0 ? (
            <div className="flex flex-col gap-3">
              <div className="flex flex-col items-center gap-2 rounded-2xl border border-dashed border-line2 px-6 py-12 text-center">
                <p className="text-[16px] font-bold text-ink2">아직 연결 요청이 없어요</p>
                <p className="text-[14px] leading-6 text-muted">
                  기관이 아이를 등록하면 여기에 나타납니다. 등록을 마쳤다고 들으셨다면 잠시 뒤
                  다시 확인해 주세요.
                </p>
              </div>
              <button
                type="button"
                onClick={() => revalidator.revalidate()}
                disabled={revalidator.state === "loading"}
                className={primary}
              >
                {revalidator.state === "loading" ? "확인 중…" : "다시 확인"}
              </button>
              <p className="text-[13px] leading-6 text-muted">
                기관에서 받은 기관 코드가 있다면 [설정 › 기관 권한]에서 직접 연결할 수 있습니다.
              </p>
            </div>
          ) : (
            <ul className="flex flex-col gap-2.5">
              {links.map((link) => (
                <li key={`${link.child.id}:${link.institution.id}`}>
                  <button
                    type="button"
                    disabled={pending}
                    onClick={() =>
                      start(() =>
                        navigate(
                          `/parent/consent?childId=${encodeURIComponent(link.child.id)}&institutionId=${encodeURIComponent(link.institution.id)}`,
                        ),
                      )
                    }
                    className="tap flex w-full items-center gap-3 rounded-2xl border border-line px-4 py-3 text-left hover:border-accent"
                  >
                    <InstitutionIcon type={link.institution.type} />
                    <span className="flex min-w-0 flex-1 flex-col gap-1">
                      <span className="text-[16px] font-bold">
                        {link.child.name} · {link.child.birthDate.replaceAll("-", ".")}생
                      </span>
                      <InstitutionChip institution={link.institution} withName />
                      {link.requestedAt ? (
                        <span className="text-[13px] text-muted">
                          {link.requestedAt.slice(0, 10).replaceAll("-", ".")} 요청
                        </span>
                      ) : null}
                    </span>
                    <span aria-hidden className="text-[18px] text-muted">
                      ›
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </div>
  );
}
