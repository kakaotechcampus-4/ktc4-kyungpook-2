import { useCallback, useEffect, useRef, useState } from "react";
import { Busy } from "@/components/ui";
import { TermsSheet } from "@/components/TermsSheet";
import { getTerms, type TermKey, type TermsDocument } from "@/lib/auth";

export type TermItem = { key: TermKey; label: string; required: boolean };

export type TermsDocs =
  | { status: "loading" }
  | { status: "failed" }
  | { status: "mismatch" }
  | { status: "ready"; byKey: ReadonlyMap<TermKey, TermsDocument> };

export const TERMS_CHANGED_MESSAGE = "약관이 바뀌었어요. 바뀐 약관을 확인하고 다시 동의해 주세요.";

function sameKeys(docs: TermsDocument[], items: readonly TermItem[]): boolean {
  const keys = new Set(docs.map((d) => d.key));
  return keys.size === items.length && items.every((t) => keys.has(t.key));
}

export function useTermsDocuments(role: "parent" | "org", items: readonly TermItem[], enabled = true) {
  const [docs, setDocs] = useState<TermsDocs>({ status: "loading" });
  const current = useRef<TermsDocs>(docs);
  const latest = useRef(0);

  const load = useCallback(async (): Promise<TermsDocs> => {
    const seq = ++latest.current;
    setDocs({ status: "loading" });
    let next: TermsDocs;
    try {
      const list = await getTerms(role);
      next = sameKeys(list, items)
        ? { status: "ready", byKey: new Map(list.map((d) => [d.key, d])) }
        : { status: "mismatch" };
    } catch {
      next = { status: "failed" };
    }
    if (seq === latest.current) {
      current.current = next;
      setDocs(next);
    }
    return next;
  }, [role, items]);

  useEffect(() => {
    if (enabled) void load();
  }, [enabled, load]);

  function versionsOf(keys: TermKey[]): { key: TermKey; version: string }[] | null {
    if (docs.status !== "ready") return null;
    const picked = keys.map((key) => ({ key, version: docs.byKey.get(key)?.version }));
    return picked.every((t) => t.version) ? (picked as { key: TermKey; version: string }[]) : null;
  }

  async function reloadChanged(): Promise<TermKey[]> {
    const before = current.current;
    const after = await load();
    if (before.status !== "ready" || after.status !== "ready") return items.map((t) => t.key);
    return items.filter((t) => before.byKey.get(t.key)?.version !== after.byKey.get(t.key)?.version).map((t) => t.key);
  }

  return { docs, retry: load, versionsOf, reloadChanged };
}

export function TermsForm({
  terms,
  docs,
  onRetry,
  agreed,
  onChange,
  pending,
  error,
  onSubmit,
  rounded = "rounded-2xl",
  buttonClassName,
}: {
  terms: readonly TermItem[];
  docs: TermsDocs;
  onRetry: () => void;
  agreed: TermKey[];
  onChange: (agreed: TermKey[]) => void;
  pending: boolean;
  error: string | null;
  onSubmit: () => void;
  rounded?: string;
  buttonClassName: string;
}) {
  const [reading, setReading] = useState<TermsDocument | null>(null);
  const opener = useRef<HTMLButtonElement | null>(null);
  const requiredMissing = terms.some((t) => t.required && !agreed.includes(t.key));
  const ready = docs.status === "ready";

  function closeSheet() {
    setReading(null);
    requestAnimationFrame(() => opener.current?.focus());
  }

  if (docs.status === "mismatch") {
    return (
      <div role="alert" className={`flex flex-col gap-3 ${rounded} border border-line2 px-4 py-4`}>
        <p className="text-[15px] leading-7 text-ink">약관 목록이 바뀌었어요. 새로고침해 주세요.</p>
        <button type="button" onClick={() => window.location.reload()} className={buttonClassName}>
          새로고침
        </button>
      </div>
    );
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (ready) onSubmit();
      }}
      className="flex flex-col gap-3"
    >
      <ul className="flex flex-col gap-2">
        {terms.map((t) => {
          const on = agreed.includes(t.key);
          const doc = ready ? docs.byKey.get(t.key) : undefined;
          return (
            <li
              key={t.key}
              className={`flex items-center ${rounded} border border-line2 has-checked:border-accent has-checked:bg-accentsoft`}
            >
              <label className="tap flex min-w-0 flex-1 cursor-pointer items-center gap-3 pl-4 has-disabled:cursor-default">
                <input
                  type="checkbox"
                  checked={on}
                  disabled={pending}
                  onChange={() => onChange(on ? agreed.filter((k) => k !== t.key) : [...agreed, t.key])}
                  className="size-5 shrink-0 accent-accent"
                />
                <span className="flex flex-1 flex-wrap items-center gap-x-2 gap-y-1 py-2 text-[15px]">
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
              <button
                type="button"
                disabled={!doc}
                aria-label={`${doc?.title ?? t.label} 보기`}
                onClick={(e) => {
                  if (!doc) return;
                  opener.current = e.currentTarget;
                  setReading(doc);
                }}
                className="tap shrink-0 px-4 text-[14px] font-semibold text-ink2 underline underline-offset-4 hover:text-ink disabled:text-muted disabled:no-underline"
              >
                보기
              </button>
            </li>
          );
        })}
      </ul>
      {docs.status === "loading" ? (
        <p className="text-[14px] text-muted">
          <Busy>약관을 불러오는 중…</Busy>
        </p>
      ) : null}
      {docs.status === "failed" ? (
        <div
          role="alert"
          className={`flex items-center justify-between gap-3 ${rounded} bg-blocksoft px-4 py-2.5 text-[14px] leading-6 text-block`}
        >
          약관을 불러오지 못했어요.
          <button
            type="button"
            onClick={onRetry}
            className="tap shrink-0 font-semibold underline underline-offset-4"
          >
            다시 시도
          </button>
        </div>
      ) : null}
      {error ? (
        <p role="alert" className={`${rounded} bg-blocksoft px-4 py-3 text-[14px] leading-6 text-block`}>
          {error}
        </p>
      ) : null}
      <button disabled={requiredMissing || pending || !ready} className={buttonClassName}>
        {pending ? <Busy>저장 중…</Busy> : "동의하고 계속하기"}
      </button>
      {requiredMissing ? <p className="text-center text-[14px] text-muted">필수 동의를 선택해주세요</p> : null}
      {reading ? <TermsSheet doc={reading} onClose={closeSheet} /> : null}
    </form>
  );
}
