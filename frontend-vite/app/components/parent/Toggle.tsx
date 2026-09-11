/** iOS 스타일 토글 스위치 — 프로토타입의 기관 권한 on/off 시각을 재현한다. */
export function Toggle({
  on,
  onChange,
  label,
}: {
  on: boolean;
  onChange: () => void;
  label?: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      onClick={onChange}
      className={`relative inline-flex h-8 w-14 shrink-0 items-center rounded-full transition-colors duration-200 ${
        on ? "bg-accent" : "bg-surface2"
      }`}
    >
      <span
        className={`inline-block size-6 rounded-full bg-white shadow transition-transform duration-200 ${
          on ? "translate-x-7" : "translate-x-1"
        }`}
      />
    </button>
  );
}
