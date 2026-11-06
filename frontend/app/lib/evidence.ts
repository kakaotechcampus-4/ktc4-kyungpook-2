/**
 * 검토 팝업 [근거] — 요약 문장의 근거 인용(O-30 `claims[].evidence[].quote`)을 원본 일지에서 찾아 강조한다.
 *
 * AI 가 인용을 원문과 글자 하나 다르게(띄어쓰기·문장부호) 보낼 때가 있다. 그래서 먼저 그대로 찾고, 없으면
 * 공백·문장부호를 빼고 다시 찾는다. 그래도 없으면 강조하지 않는다 — 화면은 "근거 위치를 못 찾았어요" 를 보인다.
 */

/** 공백 · 문장부호(따옴표·마침표·쉼표 등) — 느슨히 찾을 때 무시한다 */
const LOOSE = /[\s\p{P}\p{S}]/u;

/** text 안에서 quote 가 있는 [시작, 끝) — 없으면 null */
export function findQuote(text: string, quote: string): [number, number] | null {
  const q = quote.trim();
  if (!q) return null;
  const at = text.indexOf(q);
  if (at >= 0) return [at, at + q.length];

  // 느슨히: 무시할 글자를 뺀 문자열과 원래 위치 표를 만든다
  const kept: number[] = [];
  let flat = "";
  for (let i = 0; i < text.length; i++) {
    if (LOOSE.test(text[i])) continue;
    kept.push(i);
    flat += text[i];
  }
  const fq = [...q].filter((c) => !LOOSE.test(c)).join("");
  if (!fq) return null;
  const k = flat.indexOf(fq);
  if (k < 0) return null;
  return [kept[k], kept[k + fq.length - 1] + 1];
}

export interface Segment {
  text: string;
  marked: boolean;
}

/** text 를 강조 구간과 나머지로 나눈다. 겹치는 구간은 앞의 것만 쓴다 */
export function markSegments(text: string, ranges: [number, number][]): Segment[] {
  const sorted = [...ranges].sort((a, b) => a[0] - b[0]);
  const out: Segment[] = [];
  let pos = 0;
  for (const [s, e] of sorted) {
    if (s < pos) continue;
    if (s > pos) out.push({ text: text.slice(pos, s), marked: false });
    out.push({ text: text.slice(s, e), marked: true });
    pos = e;
  }
  if (pos < text.length) out.push({ text: text.slice(pos), marked: false });
  return out;
}

/**
 * 고른 문장의 근거를 일지마다 찾는다. 인용이 가리킨 일지에서 먼저 찾고, 없으면 다른 일지에서도 찾는다.
 * `found` 가 false 면 근거는 있는데 원문에서 위치를 못 찾은 것이다.
 */
export function locateEvidence(
  entries: { journalEntryId: string; content: string }[],
  evidence: { journalEntryId: string; quote: string }[],
): { ranges: Map<string, [number, number][]>; found: boolean } {
  const ranges = new Map<string, [number, number][]>();
  let found = false;
  for (const ev of evidence) {
    const own = entries.filter((e) => e.journalEntryId === ev.journalEntryId);
    const order = [...own, ...entries.filter((e) => e.journalEntryId !== ev.journalEntryId)];
    for (const e of order) {
      const hit = findQuote(e.content, ev.quote);
      if (!hit) continue;
      ranges.set(e.journalEntryId, [...(ranges.get(e.journalEntryId) ?? []), hit]);
      found = true;
      break;
    }
  }
  return { ranges, found };
}
