"use client";

import { useState, useTransition } from "react";
import { useRouter } from "next/navigation";

type Step = "code" | "phone" | "otp" | "otp_expired" | "invite_expired";

export default function ParentInvitePage() {
  const [step, setStep] = useState<Step>("code");
  const [code, setCode] = useState("");
  const [phone, setPhone] = useState("");
  const [otp, setOtp] = useState("");
  const [pending, start] = useTransition();
  const router = useRouter();

  const field =
    "tap w-full rounded border border-line2 px-3 text-[16px] outline-none focus:border-accent";
  const primary =
    "tap w-full rounded bg-accent px-4 text-[16px] font-semibold text-white hover:bg-accentink disabled:opacity-50";

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="mb-1.5 text-[20px] font-bold tracking-tight">
          우리 아이 기록 공유에 동의하기
        </h1>
        <p className="text-[15px] leading-7 text-ink2">
          기관에서 받은 초대코드를 입력하면, 어떤 정보가 공유되는지 확인하고 직접 동의 범위를
          정할 수 있어요.
        </p>
      </div>

      {step === "code" ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            setStep(code.trim().toUpperCase() === "EXPIRED" ? "invite_expired" : "phone");
          }}
          className="flex flex-col gap-3"
        >
          <label className="flex flex-col gap-1.5">
            <span className="text-[15px] font-semibold">초대코드</span>
            <input
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder="ITDA-0000-0000"
              required
              className={`${field} font-mono tracking-widest`}
            />
          </label>
          <button className={primary}>다음</button>
          <p className="text-[13px] leading-6 text-muted">
            링크를 눌러 들어오셨다면 코드가 이미 채워져 있어요.
          </p>
        </form>
      ) : null}

      {step === "invite_expired" ? (
        <div className="flex flex-col gap-3">
          <p className="rounded border border-block/40 bg-blocksoft px-3 py-2.5 text-[15px] leading-6 text-block">
            초대코드가 만료되었어요. 기관에 새 코드를 요청해주세요.
          </p>
          <button onClick={() => setStep("code")} className={primary}>
            코드 다시 입력
          </button>
        </div>
      ) : null}

      {step === "phone" ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            setStep("otp");
          }}
          className="flex flex-col gap-3"
        >
          <label className="flex flex-col gap-1.5">
            <span className="text-[15px] font-semibold">보호자 휴대폰 번호</span>
            <input
              type="tel"
              inputMode="numeric"
              autoComplete="tel"
              required
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder="010-0000-0000"
              className={field}
            />
          </label>
          <button className={primary}>인증번호 받기</button>
          <p className="text-[13px] leading-6 text-muted">
            번호는 본인 확인에만 쓰이고, 기관이 입력한 것이 아니라 보호자가 직접 입력한
            값입니다.
          </p>
        </form>
      ) : null}

      {step === "otp" || step === "otp_expired" ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            start(() => router.push("/parent/consent"));
          }}
          className="flex flex-col gap-3"
        >
          <p className="text-[15px] text-ink2">
            <b className="font-semibold">{phone}</b> 으로 인증번호를 보냈어요.
          </p>
          <label className="flex flex-col gap-1.5">
            <span className="text-[15px] font-semibold">인증번호 6자리</span>
            <input
              inputMode="numeric"
              maxLength={6}
              required
              value={otp}
              onChange={(e) => setOtp(e.target.value)}
              placeholder="000000"
              className={`${field} tracking-[0.3em] tabular-nums`}
            />
          </label>
          {step === "otp_expired" ? (
            <p className="rounded border border-block/40 bg-blocksoft px-3 py-2 text-[14px] text-block">
              인증번호 유효시간이 만료되었어요. 다시 받아주세요.
            </p>
          ) : null}
          <button disabled={pending} className={primary}>
            {pending ? "확인 중…" : "확인"}
          </button>
          <button
            type="button"
            onClick={() => setStep("otp_expired")}
            className="text-[14px] text-muted underline"
          >
            만료 상태 보기
          </button>
        </form>
      ) : null}
    </div>
  );
}
