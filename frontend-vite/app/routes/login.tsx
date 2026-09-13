import { useState, useTransition } from "react";
import { useNavigate } from "react-router";
import { signIn } from "@/lib/auth";

type Step = "phone" | "otp" | "otp_expired";

export default function LoginPage() {
  const [step, setStep] = useState<Step>("phone");
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [pending, start] = useTransition();
  const navigate = useNavigate();

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-[420px]">
        <div className="mb-7 flex items-center gap-2.5">
          <span
            aria-hidden
            className="flex size-10 items-center justify-center rounded bg-accent text-[17px] font-bold text-white"
          >
            잇
          </span>
          <div>
            <p className="text-[19px] font-bold tracking-tight">잇다 ITDA</p>
            <p className="text-[14px] text-muted">기관 담당자 로그인</p>
          </div>
        </div>

        <div className="rounded border border-line bg-surface p-6">
          {step === "phone" ? (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                setStep("otp");
              }}
              className="flex flex-col gap-4"
            >
              <label className="flex flex-col gap-1.5">
                <span className="text-[15px] font-semibold">휴대폰 번호</span>
                <input
                  type="tel"
                  inputMode="numeric"
                  autoComplete="tel"
                  required
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  placeholder="010-0000-0000"
                  className="tap rounded border border-line2 px-3 text-[16px] outline-none focus:border-accent"
                />
              </label>
              <button
                type="submit"
                className="tap rounded bg-accent px-4 text-[16px] font-semibold text-white hover:bg-accentink"
              >
                인증번호 받기
              </button>
              <p className="text-[13px] leading-6 text-muted">
                학부모라면 기관에서 받은 초대 링크로 들어와 주세요.
              </p>
            </form>
          ) : (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                start(async () => {
                  await signIn("org");
                  navigate("/dashboard");
                });
              }}
              className="flex flex-col gap-4"
            >
              <p className="text-[15px] text-ink2">
                <b className="font-semibold">{phone || "010-0000-0000"}</b> 으로 인증번호를
                보냈습니다.
              </p>
              <label className="flex flex-col gap-1.5">
                <span className="text-[15px] font-semibold">인증번호 6자리</span>
                <input
                  inputMode="numeric"
                  maxLength={6}
                  required
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  placeholder="000000"
                  className="tap rounded border border-line2 px-3 text-[18px] tracking-[0.3em] tabular-nums outline-none focus:border-accent"
                />
              </label>

              {step === "otp_expired" ? (
                <p className="rounded border border-block/40 bg-blocksoft px-3 py-2 text-[14px] text-block">
                  인증번호 유효시간이 만료되었습니다. 다시 받아주세요.
                </p>
              ) : null}

              <button
                type="submit"
                disabled={pending}
                className="tap rounded bg-accent px-4 text-[16px] font-semibold text-white hover:bg-accentink disabled:opacity-50"
              >
                {pending ? "확인 중…" : "로그인"}
              </button>
              <div className="flex gap-3 text-[14px]">
                <button
                  type="button"
                  onClick={() => setStep("phone")}
                  className="text-accentink underline"
                >
                  번호 다시 입력
                </button>
                <button
                  type="button"
                  onClick={() => setStep("otp_expired")}
                  className="text-muted underline"
                >
                  만료 상태 보기
                </button>
              </div>
            </form>
          )}
        </div>
      </div>
    </div>
  );
}
