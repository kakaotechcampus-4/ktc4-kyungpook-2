"use client";

import { useState } from "react";
import { InstitutionChip } from "@/components/ui";
import { PARENT_CHILD } from "@/lib/mock/data";

const FIELDS = [
  { key: "daily_summary", label: "일일 요약" },
  { key: "weekly_insight", label: "주간 인사이트" },
] as const;

export default function ParentConsentManagePage() {
  const [scopes, setScopes] = useState<Record<string, string[]>>(
    Object.fromEntries(
      PARENT_CHILD.institutions.map((i) => [
        i.institution.id,
        i.consent === "granted" ? FIELDS.map((f) => f.key) : [],
      ]),
    ),
  );
  const [confirming, setConfirming] = useState<string | null>(null);

  const toggle = (instId: string, key: string) =>
    setScopes((s) => ({
      ...s,
      [instId]: s[instId].includes(key)
        ? s[instId].filter((k) => k !== key)
        : [...s[instId], key],
    }));

  const revoke = (instId: string) => {
    setScopes((s) => ({ ...s, [instId]: [] }));
    setConfirming(null);
  };

  const target = PARENT_CHILD.institutions.find((i) => i.institution.id === confirming);

  return (
    <div className="flex flex-col gap-5">
      <h1 className="text-[20px] font-bold tracking-tight">동의 관리</h1>

      {/* 회수의 한계를 화면에서 먼저 말한다 — 없으면 "회수하면 다 지워진다"고 오해한다 */}
      <p className="flex items-start gap-2 rounded border border-human/50 bg-humansoft px-3 py-2.5 text-[15px] leading-6 text-human">
        <span aria-hidden className="font-bold">
          !
        </span>
        <span>
          회수해도 <b className="font-semibold">이미 보낸 정보는 회수되지 않아요.</b> 앞으로
          보낼 정보만 막아요.
        </span>
      </p>

      {PARENT_CHILD.institutions.map(({ institution }) => {
        const on = scopes[institution.id] ?? [];
        return (
          <section
            key={institution.id}
            className="rounded border border-line px-4 py-4"
          >
            <div className="mb-3">
              <InstitutionChip institution={institution} withName />
            </div>

            <ul className="mb-3 flex flex-col gap-2">
              {FIELDS.map((f) => (
                <li key={f.key}>
                  <label className="flex cursor-pointer items-center justify-between gap-3">
                    <span className="text-[16px]">{f.label}</span>
                    <input
                      type="checkbox"
                      checked={on.includes(f.key)}
                      onChange={() => toggle(institution.id, f.key)}
                      className="size-5"
                    />
                  </label>
                </li>
              ))}
            </ul>

            {on.length === 0 ? (
              <p className="text-[14px] text-muted">
                이 기관에는 새로운 정보가 전달되지 않아요.
              </p>
            ) : (
              <button
                onClick={() => setConfirming(institution.id)}
                className="tap w-full rounded border border-block/40 text-[15px] font-semibold text-block hover:bg-blocksoft"
              >
                이 기관 공유 회수하기
              </button>
            )}
          </section>
        );
      })}

      {target ? (
        <div
          role="dialog"
          aria-modal
          className="fixed inset-0 z-10 flex items-end justify-center bg-ink/40 px-4 pb-6"
        >
          <div className="w-full max-w-[400px] rounded-lg bg-surface p-5">
            <p className="mb-2 text-[17px] font-bold">
              {target.institution.name} 공유를 회수할까요?
            </p>
            <p className="mb-5 text-[15px] leading-7 text-ink2">
              회수하면 앞으로 이 기관에 새로운 정보가 전달되지 않아요.{" "}
              <b className="font-semibold">이미 보낸 정보는 회수되지 않아요.</b>
            </p>
            <div className="flex gap-2">
              <button
                onClick={() => setConfirming(null)}
                className="tap flex-1 rounded border border-line2 text-[16px] font-semibold text-ink2"
              >
                취소
              </button>
              <button
                onClick={() => revoke(target.institution.id)}
                className="tap flex-1 rounded bg-block text-[16px] font-semibold text-white"
              >
                회수하기
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
