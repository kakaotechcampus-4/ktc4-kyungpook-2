import { useState } from "react";
import { redirect, useNavigate } from "react-router";
import { TERMS_CHANGED_MESSAGE, TermsForm, useTermsDocuments, type TermItem } from "@/components/TermsForm";
import { ApiError } from "@/lib/api";
import { agreeTerms, getSession, homePathFor, signOut, type TermKey } from "@/lib/auth";

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
      setError("동의를 저장하지 못했어요. 다시 시도해 주세요.");
    }
  }

  async function logout() {
    setSigningOut(true);
    await signOut();
    navigate("/login", { replace: true });
  }

  return (
    <div
      className="flex min-h-screen items-center justify-center px-4 py-10 break-keep"
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
          <p className="text-[14px] text-muted">약관 동의</p>
        </div>

        <div className="flex flex-col gap-5 rounded-[22px] border border-line bg-surface p-6 shadow-[0_10px_30px_rgba(120,90,40,0.07)]">
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
            rounded="rounded-2xl"
            buttonClassName="tap h-12 w-full rounded-full bg-ink px-4 text-[16px] font-semibold text-paper hover:brightness-110 disabled:bg-surface2 disabled:text-muted"
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
