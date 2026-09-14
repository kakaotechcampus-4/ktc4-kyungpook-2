import { useState, useTransition } from "react";
import { useNavigate } from "react-router";
import { StepProgress } from "@/components/parent/StepProgress";

type Step = "intro" | "code" | "phone" | "otp" | "otp_expired" | "invite_expired" | "terms";

const TERMS = [
  { key: "tos", label: "서비스 이용약관 동의", required: true },
  { key: "privacy", label: "개인정보와 민감정보 수집 동의", required: true },
  { key: "notify", label: "새로운 소식 알림 받기", required: false },
] as const;

export default function ParentInvitePage() {
  const [step, setStep] = useState<Step>("intro");
  const [code, setCode] = useState("");
  const [phone, setPhone] = useState("");
  const [otp, setOtp] = useState("");
  const [agreed, setAgreed] = useState<string[]>([]);
  const [pending, start] = useTransition();
  const navigate = useNavigate();

  const field =
    "tap w-full rounded-2xl border border-line2 px-4 text-[16px] outline-none focus:border-accent";
  const primary =
    "tap h-14 w-full rounded-2xl bg-accent px-4 text-[16px] font-bold text-white hover:bg-accentink disabled:bg-surface2 disabled:text-muted";
  const requiredMissing = TERMS.some((t) => t.required && !agreed.includes(t.key));

  return (
    <div className="flex flex-col gap-6">
      {step === "intro" ? (
        <div className="flex flex-col gap-6">
          <div>
            <h1 className="mb-2 text-[26px] leading-[1.3] font-extrabold tracking-tight">
              흩어진 돌봄 기록을
              <br />한 곳에서 봅니다
            </h1>
            <p className="text-[15px] leading-7 text-ink2">
              학교와 센터, 학원에서 따로 오던 소식을 아이별로 모읍니다. 정보는 보호자가 허락한
              기관에만 공유됩니다.
            </p>
          </div>
          <button onClick={() => setStep("code")} className={primary}>
            시작하기
          </button>
        </div>
      ) : null}

      {step !== "intro" ? (
        <div className="flex flex-col gap-4">
          <StepProgress step={step === "terms" ? 2 : 1} total={4} />
          <div>
            <h1 className="mb-1.5 text-[20px] font-extrabold tracking-tight">
              우리 아이 기록 공유에 동의하기
            </h1>
            <p className="text-[15px] leading-7 text-ink2">
              기관에서 받은 초대코드를 입력하면, 어떤 정보가 공유되는지 확인하고 직접 동의 범위를
              정할 수 있어요.
            </p>
          </div>
        </div>
      ) : null}

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
          <p className="rounded-2xl border border-block/40 bg-blocksoft px-4 py-3 text-[15px] leading-6 text-block">
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
            setStep("terms");
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
            <p className="rounded-2xl border border-block/40 bg-blocksoft px-4 py-3 text-[14px] text-block">
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

      {step === "terms" ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            start(() => navigate("/parent/consent"));
          }}
          className="flex flex-col gap-3"
        >
          <ul className="flex flex-col gap-2">
            {TERMS.map((t) => {
              const on = agreed.includes(t.key);
              return (
                <li key={t.key}>
                  <label className="tap flex cursor-pointer items-center gap-3 rounded-2xl border border-line2 px-4 has-checked:border-accent has-checked:bg-accentsoft">
                    <input
                      type="checkbox"
                      checked={on}
                      onChange={() =>
                        setAgreed((a) =>
                          on ? a.filter((k) => k !== t.key) : [...a, t.key],
                        )
                      }
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
          <button disabled={pending || requiredMissing} className={primary}>
            {pending ? "확인 중…" : "가입하고 시작하기"}
          </button>
          {requiredMissing ? (
            <p className="text-center text-[14px] text-muted">필수 동의를 선택해주세요</p>
          ) : null}
        </form>
      ) : null}
    </div>
  );
}
