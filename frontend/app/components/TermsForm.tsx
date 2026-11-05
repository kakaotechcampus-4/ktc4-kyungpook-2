import { Busy } from "@/components/ui";
import type { TermKey } from "@/lib/auth";

export type TermItem = { key: TermKey; label: string; required: boolean };

/**
 * 약관 체크박스 목록 · 오류 · "동의하고 계속하기". 보호자 초대 화면(P-01)과 기관 약관 화면(I-16)이 쓴다.
 *
 * 체크 상태는 부르는 화면이 쥔다 — 보호자 화면은 코드 단계에서 TERMS_NOT_AGREED 로 돌아오면 체크를 비운다.
 * 저장 중에는 체크박스도 잠가 보낸 약관과 화면의 체크가 어긋나지 않게 한다.
 * 모양은 화면마다 다르다(보호자 = 둥근 모서리, 기관 = 회원가입 화면과 같은 각진 모서리) — `rounded` · `buttonClassName`.
 */
export function TermsForm({
  terms,
  agreed,
  onChange,
  pending,
  error,
  onSubmit,
  rounded = "rounded-2xl",
  buttonClassName,
}: {
  terms: readonly TermItem[];
  agreed: TermKey[];
  onChange: (agreed: TermKey[]) => void;
  pending: boolean;
  error: string | null;
  onSubmit: () => void;
  rounded?: string;
  buttonClassName: string;
}) {
  const requiredMissing = terms.some((t) => t.required && !agreed.includes(t.key));

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit();
      }}
      className="flex flex-col gap-3"
    >
      <ul className="flex flex-col gap-2">
        {terms.map((t) => {
          const on = agreed.includes(t.key);
          return (
            <li key={t.key}>
              <label
                className={`tap flex cursor-pointer items-center gap-3 ${rounded} border border-line2 px-4 has-checked:border-accent has-checked:bg-accentsoft has-disabled:cursor-default`}
              >
                <input
                  type="checkbox"
                  checked={on}
                  disabled={pending}
                  onChange={() => onChange(on ? agreed.filter((k) => k !== t.key) : [...agreed, t.key])}
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
      {error ? (
        <p role="alert" className={`${rounded} bg-blocksoft px-4 py-3 text-[14px] leading-6 text-block`}>
          {error}
        </p>
      ) : null}
      <button disabled={requiredMissing || pending} className={buttonClassName}>
        {pending ? <Busy>저장 중…</Busy> : "동의하고 계속하기"}
      </button>
      {requiredMissing ? <p className="text-center text-[14px] text-muted">필수 동의를 선택해주세요</p> : null}
    </form>
  );
}
