import { useState } from "react";
import { Bell, Check, ChevronDown } from "lucide-react";
import { Link, useNavigate } from "react-router";
import { useChildContext } from "@/components/parent/ChildContext";
import { TourButton, type TourScreen } from "@/components/Tour";

export function ParentPageHeader({
  title,
  subtitle,
  showChildSwitch = false,
  back,
  alert = false,
  help,
}: {
  title: string;
  subtitle?: string;
  showChildSwitch?: boolean;
  back?: boolean;
  alert?: boolean;
  help?: TourScreen;
}) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const { kids, selected, selectChild, hasUnreadReport, hasPendingRequest } = useChildContext();
  const needsAttention = (id: string) => hasUnreadReport(id) || hasPendingRequest(id);
  const otherChildAttention = kids.length > 1 && kids.some((k) => k.id !== selected.id && needsAttention(k.id));

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
          aria-label={otherChildAttention ? "아이 전환, 다른 아이에게 확인할 것 있음" : "아이 전환"}
          className="tap relative flex shrink-0 items-center gap-1 rounded-full"
        >
          <span
            aria-hidden
            className="flex size-11 items-center justify-center rounded-full bg-surface2 text-[16px] font-bold text-ink2"
          >
            {selected.name.slice(0, 1)}
          </span>
          {otherChildAttention ? (
            <span
              aria-hidden
              className="absolute left-8 top-0 size-2.5 rounded-full bg-human ring-2 ring-surface"
            />
          ) : null}
          {kids.length > 1 ? (
            <ChevronDown size={16} strokeWidth={2.5} className="text-muted" />
          ) : null}
        </button>
      ) : null}

      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <h1 className="truncate text-[21px] font-extrabold tracking-tight">{title}</h1>
          {help ? <TourButton screen={help} auto /> : null}
        </div>
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
            className="absolute right-1 top-1 size-2.5 rounded-full bg-mark ring-2 ring-surface"
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
              const reasons = [
                hasUnreadReport(k.id) ? "안 본 리포트 있음" : null,
                hasPendingRequest(k.id) ? "권한 요청 있음" : null,
              ].filter((r) => r !== null);
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
                  <span className="min-w-0 flex-1 truncate text-left">
                    {k.name}
                    {reasons.length > 0 ? <span className="sr-only">, {reasons.join(", ")}</span> : null}
                  </span>
                  {reasons.length > 0 ? (
                    <span aria-hidden className="size-2.5 shrink-0 rounded-full bg-human" />
                  ) : null}
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
