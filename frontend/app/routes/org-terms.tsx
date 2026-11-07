import { useState } from "react";
import { redirect, useNavigate } from "react-router";
import { TERMS_CHANGED_MESSAGE, TermsForm, useTermsDocuments, type TermItem } from "@/components/TermsForm";
import { ApiError } from "@/lib/api";
import { agreeTerms, getSession, homePathFor, signOut, type TermKey } from "@/lib/auth";

/**
 * I-16 기관 약관 동의 — 기관 담당자가 가입 뒤 처음, 또는 다시 받는 약관의 판이 올라간 뒤 한 번 본다.
 *
 * 기관 화면 묶음(`org/layout.tsx`) 밖에 둔다. 그 가드가 동의하지 않은 기관을 이리 보내므로 안에 두면 돈다.
 * 머리띠 · 메뉴 없이 회원가입 화면과 같은 모양이다. 동의하지 않으면 기관 화면을 하나도 쓸 수 없어서,
 * 잘못 가입했거나 그만두려는 사람이 나갈 수 있게 아래에 로그아웃을 둔다.
 * 화면이 열리면 기관 약관 본문을 받아 두고, 동의는 그 판으로 보낸다(줄마다 [보기]로 본문을 연다).
 */
const TERMS: readonly TermItem[] = [
  { key: "org_tos", label: "기관 서비스 이용약관 동의", required: true },
  { key: "org_privacy", label: "담당자 개인정보 수집 동의", required: true },
  { key: "org_child_data", label: "아동 정보 보호 서약", required: true },
];

export async function clientLoader() {
  const session = await getSession();
  if (!session.loggedIn) return redirect("/login");
  if (!session.signupCompleted || !session.role) return redirect("/signup?role=org");
  if (session.role !== "org" || session.termsAgreed) {
    return redirect(homePathFor(session.role, session.termsAgreed));
  }
  return null;
}

export default function OrgTermsPage() {
  const navigate = useNavigate();
  const [agreed, setAgreed] = useState<TermKey[]>([]);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [signingOut, setSigningOut] = useState(false);
  const terms = useTermsDocuments("org", TERMS);

  async function submit() {
    const picked = terms.versionsOf(agreed);
    if (pending || !picked) return;
    setError(null);
    setPending(true);
    try {
      await agreeTerms(picked);
      navigate("/dashboard", { replace: true });
    } catch (err) {
      setPending(false);
      if (err instanceof ApiError && err.code === "TERMS_VERSION_CHANGED") {
        // 화면을 연 뒤 약관이 바뀌었다 — 바뀐 약관의 체크만 풀고 다시 보게 한다
        const changed = await terms.reloadChanged();
        setAgreed((prev) => prev.filter((k) => !changed.includes(k)));
        setError(TERMS_CHANGED_MESSAGE);
        return;
      }
      if (err instanceof ApiError && err.status === 401) {
        navigate("/login", { replace: true });
        return;
      }
      if (err instanceof ApiError && err.code === "SIGNUP_NOT_COMPLETED") {
        navigate("/signup?role=org", { replace: true });
        return;
      }
      // 체크는 그대로 두고 다시 누를 수 있게 한다
      setError("동의를 저장하지 못했어요. 다시 시도해 주세요.");
    }
  }

  async function logout() {
    setSigningOut(true);
    await signOut();
    navigate("/login", { replace: true });
  }

  return (
    <div className="flex min-h-screen items-center justify-center px-4 py-10 break-keep">
      <div className="w-full max-w-[460px]">
        <div className="mb-7 flex items-center gap-2.5">
          <span
            aria-hidden
            className="flex size-10 items-center justify-center rounded bg-ink text-[17px] font-bold text-paper"
          >
            잇
          </span>
          <div>
            <p className="text-[19px] font-bold tracking-tight">잇다 ITDA</p>
            <p className="text-[14px] text-muted">약관 동의</p>
          </div>
        </div>

        <div className="flex flex-col gap-5 rounded border border-line bg-surface p-6">
          <div>
            <h1 className="mb-1.5 text-[20px] font-bold tracking-tight">기관 서비스 이용에 동의해 주세요</h1>
            <p className="text-[15px] leading-7 text-ink2">
              보호자가 공유한 아이 정보를 다루는 기관의 약속까지 함께 받아요.
            </p>
          </div>

          <TermsForm
            terms={TERMS}
            docs={terms.docs}
            onRetry={() => void terms.retry()}
            agreed={agreed}
            onChange={setAgreed}
            pending={pending}
            error={error}
            onSubmit={() => void submit()}
            rounded="rounded"
            buttonClassName="tap h-12 w-full rounded bg-ink px-4 text-[16px] font-semibold text-paper hover:brightness-110 disabled:bg-surface2 disabled:text-muted"
          />
        </div>

        <p className="mt-5 text-center">
          <button
            type="button"
            onClick={() => void logout()}
            disabled={signingOut || pending}
            className="tap text-[14px] text-muted underline underline-offset-4 hover:text-ink2 disabled:no-underline"
          >
            로그아웃
          </button>
        </p>
      </div>
    </div>
  );
}
