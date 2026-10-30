/**
 * 서버 시각(ISO, 타임존 없는 LocalDateTime 포함)을 "10.29 15:20" 처럼 짧게 보인다.
 * 서버는 한국 시간으로 저장하고, 화면도 한국에서만 쓴다.
 */
export function formatDateTime(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getMonth() + 1)}.${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 오늘 날짜를 서버 날짜와 같은 "2026-10-29" 모양으로. 기기의 지역 시간 기준이다 */
export function todayDate(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
