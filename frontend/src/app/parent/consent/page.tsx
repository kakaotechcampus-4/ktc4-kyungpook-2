"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { INVITING_INSTITUTION, PARENT_CHILD } from "@/lib/mock/data";
import { InstitutionChip } from "@/components/ui";

/**
 * P-02 확인 · 동의 — 한 화면에서 아이 확인 + 기관 확인 + 공유 범위 동의를 끝낸다.
 *
 * `defaultOn` 은 동의 항목 기본값을 켜둘 수 있는지에 대한 법률 판단이 나오기 전까지
 * 두 버전을 모두 유지하기 위한 스위치다. 화면 하단 토글로 전환해 볼 수 있다.
 */
const FIELDS = [
  {
    key: "daily_summary",
    title: "일일 요약",
    desc: "선생님이 승인한 하루 기록 요약",
  },
  {
    key: "weekly_insight",
    title: "주간 인사이트",
    desc: "여러 기록에서 찾은 반복 패턴",
  },
] as const;

export default function ParentConsentPage() {
  const [defaultOn, setDefaultOn] = useState(true);
  const [checked, setChecked] = useState<string[]>(FIELDS.map((f) => f.key));
  const [mismatch, setMismatch] = useState(false);
  const router = useRouter();

  const applyDefault = (on: boolean) => {
    setDefaultOn(on);
    setChecked(on ? FIELDS.map((f) => f.key) : []);
  };

  if (mismatch) {
    return (
      <div className="flex flex-col gap-5">
        <div className="rounded border border-block/40 bg-blocksoft px-4 py-3">
          <p className="mb-1 text-[16px] font-bold text-block">
            아이 정보가 일치하지 않아요
          </p>
          <p className="text-[15px] leading-7 text-ink2">
            화면에 표시된 이름과 생년월일이 우리 아이와 다르다면, 등록을 거부하고 기관에
            알려주세요.
          </p>
        </div>
        <div className="rounded border border-line bg-paper px-4 py-3">
          <p className="mb-1 text-[13px] font-semibold tracking-wider text-muted uppercase">
            기관이 등록한 정보
          </p>
          <p className="text-[16px] font-semibold">
            {PARENT_CHILD.name} · {PARENT_CHILD.birthDate.replaceAll("-", ".")}생
          </p>
        </div>
        <button className="tap w-full rounded bg-block px-4 text-[16px] font-semibold text-white">
          정보가 달라요 · 반려하기
        </button>
        <button
          onClick={() => setMismatch(false)}
          className="tap w-full rounded border border-line2 px-4 text-[16px] font-semibold text-ink2"
        >
          우리 아이가 맞아요
        </button>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-5">
      <p className="rounded bg-accentsoft px-3 py-2.5 text-[15px] leading-6 text-accentink">
        <b className="font-semibold">{INVITING_INSTITUTION.name}</b>에서 {PARENT_CHILD.name}{" "}
        학생을 등록했어요
      </p>

      <section>
        <h2 className="mb-2 text-[16px] font-bold">우리 아이 정보</h2>
        <div className="flex items-center gap-3 rounded border border-line px-4 py-3">
          <span
            aria-hidden
            className="flex size-10 items-center justify-center rounded-full bg-surface2 text-[16px] font-semibold text-ink2"
          >
            {PARENT_CHILD.name.slice(0, 1)}
          </span>
          <span className="text-[16px] font-semibold">
            {PARENT_CHILD.name} · {PARENT_CHILD.birthDate.replaceAll("-", ".")}생
          </span>
        </div>
        <button
          onClick={() => setMismatch(true)}
          className="mt-2 text-[14px] text-muted underline"
        >
          우리 아이가 아니에요
        </button>
      </section>

      <section>
        <h2 className="mb-2 text-[16px] font-bold">이 기관은 어디인가요</h2>
        <div className="flex flex-wrap items-center gap-2 rounded border border-line px-4 py-3">
          <InstitutionChip institution={INVITING_INSTITUTION} withName />
          <button className="ml-auto text-[14px] text-accentink underline">
            증빙서류 보기 ›
          </button>
        </div>
      </section>

      <section>
        <h2 className="mb-2 text-[16px] font-bold">무엇이 공유되나요</h2>
        <div className="flex flex-col gap-2">
          {FIELDS.map((f) => {
            const on = checked.includes(f.key);
            return (
              <label
                key={f.key}
                className="flex cursor-pointer items-start gap-3 rounded border border-line px-4 py-3 has-checked:border-accent has-checked:bg-accentsoft"
              >
                <input
                  type="checkbox"
                  checked={on}
                  onChange={() =>
                    setChecked((c) =>
                      c.includes(f.key) ? c.filter((x) => x !== f.key) : [...c, f.key],
                    )
                  }
                  className="mt-1 size-5 shrink-0"
                />
                <span>
                  <span className="block text-[16px] font-semibold">{f.title}</span>
                  <span className="block text-[14px] leading-6 text-muted">{f.desc}</span>
                </span>
              </label>
            );
          })}
        </div>
        <p className="mt-2 text-[14px] leading-6 text-muted">
          동의 후에도 언제든 [동의 관리]에서 항목을 {defaultOn ? "끄거나" : "켜거나"}{" "}
          회수할 수 있어요.
        </p>
      </section>

      {/* 하단 고정 — 스크롤 위치와 무관하게 동의 버튼이 보여야 한다 */}
      <div className="fixed bottom-0 left-1/2 w-full max-w-[430px] -translate-x-1/2 border-t border-line bg-surface px-4 py-3">
        <button
          onClick={() => router.push("/parent")}
          disabled={checked.length === 0}
          className="tap w-full rounded bg-accent px-4 text-[16px] font-semibold text-white hover:bg-accentink disabled:bg-line2 disabled:text-muted"
        >
          동의하고 시작하기
        </button>
        {checked.length === 0 ? (
          <p className="mt-1.5 text-center text-[14px] text-muted">
            공유할 항목을 한 개 이상 선택해주세요.
          </p>
        ) : null}
      </div>

      {/* 개발용 — 법률 자문 결과에 따라 하나만 남긴다 */}
      <div className="mt-2 flex items-center justify-between rounded border border-dashed border-line2 px-3 py-2 text-[13px] text-muted">
        <span>기본값 버전 (법률 확인 대기)</span>
        <span className="flex gap-1.5">
          <button
            onClick={() => applyDefault(true)}
            className={`rounded px-2 py-1 ${defaultOn ? "bg-surface2 font-semibold text-ink" : ""}`}
          >
            켜짐
          </button>
          <button
            onClick={() => applyDefault(false)}
            className={`rounded px-2 py-1 ${!defaultOn ? "bg-surface2 font-semibold text-ink" : ""}`}
          >
            꺼짐
          </button>
        </span>
      </div>
    </div>
  );
}
