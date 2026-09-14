import { useState } from "react";
import { Bell, Check, ChevronDown } from "lucide-react";
import { Link, useNavigate } from "react-router";
import { useChildContext } from "@/components/parent/ChildContext";

/**
 * 탭 화면들의 자체 헤더 — 프로토타입은 공용 브랜드 바 대신 화면마다
 * 제목+부제+알림 벨을 직접 그린다. `alert`가 true면 벨에 주황 점이 뜬다.
 *
 * `showChildSwitch` 를 켜면 왼쪽 아바타가 곧 아이 전환 버튼이 된다 —
 * 아이가 1명뿐이면 화살표 없이 아바타만 보여서 기존 한 자녀 사용자 경험은
 * 그대로 유지된다.
 */
export function ParentPageHeader({
  title,
  subtitle,
  showChildSwitch = false,
  back,
  alert = false,
}: {
  title: string;
  subtitle?: string;
  showChildSwitch?: boolean;
  back?: boolean;
  alert?: boolean;
}) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const { kids, selected, selectChild } = useChildContext();

  return (
    <header className="relative mb-5 flex items-center gap-3">
      {back ? (
        <button
          onClick={() => navigate(-1)}
          aria-label="뒤로"
          className="tap -ml-2 flex size-9 items-center justify-center text-ink2"
        >
          ‹
        </button>
      ) : showChildSwitch ? (
        <button
          onClick={() => setOpen((o) => !o)}
          aria-haspopup="listbox"
          aria-expanded={open}
          className="tap flex shrink-0 items-center gap-1 rounded-full"
        >
          <span
            aria-hidden
            className="flex size-11 items-center justify-center rounded-full bg-surface2 text-[16px] font-bold text-ink2"
          >
            {selected.name.slice(0, 1)}
          </span>
          {kids.length > 1 ? (
            <ChevronDown size={16} strokeWidth={2.5} className="text-muted" />
          ) : null}
        </button>
      ) : null}

      <div className="min-w-0 flex-1">
        <h1 className="truncate text-[21px] font-extrabold tracking-tight">{title}</h1>
        {subtitle ? <p className="truncate text-[14px] text-muted">{subtitle}</p> : null}
      </div>

      <Link
        to="/parent/notifications"
        aria-label="알림"
        className="tap relative flex size-9 shrink-0 items-center justify-center text-ink2"
      >
        <Bell size={22} strokeWidth={1.75} />
        {alert ? (
          <span
            aria-hidden
            className="absolute right-1 top-1 size-2.5 rounded-full bg-human ring-2 ring-surface"
          />
        ) : null}
      </Link>

      {open ? (
        <>
          <button
            aria-hidden
            tabIndex={-1}
            onClick={() => setOpen(false)}
            className="fixed inset-0 z-10 cursor-default"
          />
          <div
            role="listbox"
            className="absolute left-0 top-full z-20 mt-2 w-60 rounded-2xl border border-line bg-surface p-1.5 shadow-lg"
          >
            {kids.map((k) => {
              const active = k.id === selected.id;
              return (
                <button
                  key={k.id}
                  role="option"
                  aria-selected={active}
                  onClick={() => {
                    selectChild(k.id);
                    setOpen(false);
                  }}
                  className={`tap flex w-full items-center gap-2.5 rounded-xl px-2.5 text-[15px] ${
                    active ? "bg-accentsoft font-semibold text-accentink" : "text-ink2 hover:bg-surface2"
                  }`}
                >
                  <span
                    aria-hidden
                    className="flex size-8 shrink-0 items-center justify-center rounded-full bg-surface2 text-[13px] font-bold text-ink2"
                  >
                    {k.name.slice(0, 1)}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-left">{k.name}</span>
                  {active ? <Check size={16} strokeWidth={2.5} /> : null}
                </button>
              );
            })}
            <Link
              to="/parent/invite"
              onClick={() => setOpen(false)}
              className="tap flex items-center gap-2.5 rounded-xl px-2.5 text-[15px] font-semibold text-accentink hover:bg-surface2"
            >
              <span className="flex size-8 shrink-0 items-center justify-center text-[18px]">+</span>
              아이 추가
            </Link>
          </div>
        </>
      ) : null}
    </header>
  );
}
