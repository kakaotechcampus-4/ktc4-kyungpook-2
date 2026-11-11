import { ArrowLeft } from "lucide-react";
import { Link, useLoaderData, useNavigate, useNavigation, useRevalidator } from "react-router";
import { TermsArticle } from "@/components/TermsArticle";
import { Busy } from "@/components/ui";
import { getTerms, type TermKey, type TermsDocument } from "@/lib/auth";

const KEYS: readonly TermKey[] = ["tos", "privacy", "org_tos", "org_privacy", "org_child_data"];

export async function clientLoader({ params }: { params: { key?: string } }) {
  const key = KEYS.find((k) => k === params.key);
  if (!key) return { key: null, docs: [] as TermsDocument[], failed: false };
  try {
    return { key, docs: await getTerms(key.startsWith("org_") ? "org" : "parent"), failed: false };
  } catch {
    return { key, docs: [] as TermsDocument[], failed: true };
  }
}

export default function TermsPage() {
  const { key, docs, failed } = useLoaderData<typeof clientLoader>();
  const navigate = useNavigate();
  const navigation = useNavigation();
  const revalidator = useRevalidator();
  const doc = docs.find((d) => d.key === key);
  const canGoBack = typeof window !== "undefined" && (window.history.state?.idx ?? 0) > 0;

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-[720px] flex-col gap-5 px-4 py-8 break-keep">
      <div className="flex items-center gap-2.5">
        {canGoBack ? (
          <button
            type="button"
            onClick={() => navigate(-1)}
            aria-label="뒤로"
            className="tap -ml-2 grid size-10 place-items-center rounded-full text-ink2 hover:bg-surface2"
          >
            <ArrowLeft aria-hidden size={20} strokeWidth={2.2} />
          </button>
        ) : null}
        <span
          aria-hidden
          className="flex size-10 items-center justify-center rounded bg-ink text-[17px] font-bold text-paper"
        >
          잇
        </span>
        <div>
          <p className="text-[19px] font-bold tracking-tight">잇다 ITDA</p>
          <p className="text-[14px] text-muted">약관</p>
        </div>
      </div>

      {!key ? (
        <div className="flex flex-col items-start gap-3 rounded-2xl border border-line bg-surface p-6">
          <p className="text-[16px] font-bold">없는 약관이에요</p>
          <p className="text-[15px] leading-7 text-ink2">주소를 확인해 주세요.</p>
          <div className="flex flex-wrap gap-x-4 gap-y-2 text-[15px] font-semibold">
            <Link to="/terms/tos" className="text-accentink underline underline-offset-4">
              보호자 약관
            </Link>
            <Link to="/terms/org_tos" className="text-accentink underline underline-offset-4">
              기관 약관
            </Link>
          </div>
        </div>
      ) : failed ? (
        <div
          role="alert"
          className="flex items-center justify-between gap-3 rounded-2xl bg-blocksoft px-4 py-3 text-[15px] leading-6 text-block"
        >
          약관을 불러오지 못했어요.
          <button
            type="button"
            onClick={() => revalidator.revalidate()}
            disabled={revalidator.state !== "idle"}
            className="tap shrink-0 font-semibold underline underline-offset-4 disabled:no-underline"
          >
            {revalidator.state !== "idle" ? <Busy>다시 받는 중…</Busy> : "다시 시도"}
          </button>
        </div>
      ) : (
        <>
          <nav aria-label="약관" className="-mx-1 flex flex-wrap gap-1.5">
            {docs.map((d) => (
              <Link
                key={d.key}
                to={`/terms/${d.key}`}
                replace
                aria-current={d.key === key ? "page" : undefined}
                className={`rounded-full px-3.5 py-1.5 text-[14px] font-bold ${
                  d.key === key ? "bg-ink text-paper" : "bg-surface2 text-ink2 hover:text-ink"
                }`}
              >
                {d.title}
              </Link>
            ))}
          </nav>
          {doc ? (
            <article
              aria-busy={navigation.state === "loading"}
              className="flex flex-col gap-4 rounded-2xl border border-line bg-surface p-6 max-[520px]:p-5"
            >
              <h1 className="text-[20px] font-extrabold tracking-tight text-balance">{doc.title}</h1>
              <TermsArticle doc={doc} />
            </article>
          ) : null}
        </>
      )}
    </div>
  );
}
