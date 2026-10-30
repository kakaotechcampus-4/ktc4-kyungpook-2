import { useState } from "react";
import { useNavigate, useRevalidator } from "react-router";
import { Card } from "@/components/ui";
import { ApiError, acceptLinkRequest, rejectLinkRequest } from "@/lib/api";
import type { PendingLinkRequest } from "@/lib/types";

/**
 * 보호자가 우리 기관 코드로 보낸 연결 요청 하나(O-60). 관리번호를 넣어 수락하면 아이가 명부에 오른다.
 * 수락은 동의가 아니라서 보호자가 우리 기관에 동의하기 전까지는 "보호자 동의 대기"로 보인다.
 */
export function LinkRequestCard({ request }: { request: PendingLinkRequest }) {
  const revalidator = useRevalidator();
  const navigate = useNavigate();
  const [externalId, setExternalId] = useState("");
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmingReject, setConfirmingReject] = useState(false);

  const requesters = request.requesters.map((r) => r.name ?? "이름 없는 보호자").join(", ");

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    setFieldError(null);
    try {
      await action();
      revalidator.revalidate();
    } catch (err) {
      handleError(err);
    } finally {
      setBusy(false);
    }
  }

  function accept(e: React.FormEvent) {
    e.preventDefault();
    if (!externalId.trim()) {
      setFieldError("관리번호를 입력해 주세요");
      return;
    }
    run(() => acceptLinkRequest(request.child.id, externalId.trim()));
  }

  function handleError(err: unknown) {
    if (!(err instanceof ApiError)) {
      setError("네트워크 연결을 확인하고 다시 시도해 주세요.");
      return;
    }
    if (err.status === 401) {
      navigate("/login", { replace: true });
      return;
    }
    switch (err.code) {
      case "DUPLICATE_EXTERNAL_ID":
        setFieldError("이미 사용 중인 관리번호입니다");
        return;
      case "INVALID_REQUEST":
        setFieldError("관리번호를 확인해 주세요 (50자 이하)");
        return;
      case "LINK_REQUEST_NOT_FOUND":
        // 그사이 보호자가 연결을 끊었거나 다른 화면에서 처리됐다. 목록을 다시 불러오면 사라진다.
        setError("이미 처리됐거나 보호자가 거둔 요청입니다. 목록을 새로 불러왔습니다.");
        revalidator.revalidate();
        return;
      case "INSTITUTION_ALREADY_LINKED":
        setError("이미 우리 명부에 있는 아이입니다. 목록을 새로 불러왔습니다.");
        revalidator.revalidate();
        return;
    }
    if (err.status === 403) {
      setError("보안 확인에 실패했습니다. 새로고침한 뒤 다시 시도해 주세요.");
      return;
    }
    setError("잠시 후 다시 시도해 주세요.");
  }

  return (
    <Card>
      <div className="mb-3 flex flex-wrap items-baseline gap-x-2.5 gap-y-1">
        <p className="text-[17px] font-bold">{request.child.name}</p>
        <span className="text-[14px] text-muted tabular-nums">{request.child.birthDate}</span>
        <span className="ml-auto text-[14px] text-muted tabular-nums">{formatRequestedAt(request.requestedAt)} 요청</span>
      </div>
      <p className="mb-4 text-[14px] text-ink2">요청한 보호자 · {requesters}</p>

      <form onSubmit={accept} className="flex flex-wrap items-end gap-2">
        <label className="flex min-w-[200px] flex-1 flex-col gap-1.5">
          <span className="text-[15px] font-semibold">기관 내부 아동 ID</span>
          <input
            value={externalId}
            onChange={(e) => setExternalId(e.target.value)}
            maxLength={50}
            placeholder="예: 2026-0031"
            aria-invalid={fieldError ? true : undefined}
            className="tap rounded border border-line2 px-3 text-[16px] outline-none focus:border-accent aria-invalid:border-block"
          />
        </label>
        <button
          type="submit"
          disabled={busy}
          className="tap rounded bg-accent px-5 text-[15px] font-semibold text-white hover:bg-accentink disabled:opacity-60"
        >
          {busy && !confirmingReject ? "처리 중…" : "수락"}
        </button>
        {confirmingReject ? (
          <>
            <button
              type="button"
              onClick={() => setConfirmingReject(false)}
              disabled={busy}
              className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2 disabled:opacity-60"
            >
              취소
            </button>
            <button
              type="button"
              onClick={() => run(() => rejectLinkRequest(request.child.id))}
              disabled={busy}
              className="tap rounded border border-block/50 bg-blocksoft px-4 text-[15px] font-semibold text-block disabled:opacity-60"
            >
              {busy ? "처리 중…" : "거절하기"}
            </button>
          </>
        ) : (
          <button
            type="button"
            onClick={() => setConfirmingReject(true)}
            disabled={busy}
            className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2 disabled:opacity-60"
          >
            거절
          </button>
        )}
      </form>
      {fieldError ? <p className="mt-2 text-[14px] text-block">{fieldError}</p> : null}
      {confirmingReject ? (
        <p className="mt-2 text-[14px] text-ink2">
          거절해도 보호자에게 따로 알리지 않습니다. 보호자는 다시 요청할 수 있습니다.
        </p>
      ) : null}
      {error ? (
        <p role="alert" className="mt-3 text-[14px] text-block">
          {error}
        </p>
      ) : null}
    </Card>
  );
}

/** "10.29 09:20" — 날짜와 시각만. 요청은 대개 최근이라 연도는 뺀다 */
function formatRequestedAt(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getMonth() + 1)}.${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
