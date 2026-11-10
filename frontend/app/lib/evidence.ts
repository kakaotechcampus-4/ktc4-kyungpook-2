const LOOSE = /[\s\p{P}\p{S}]/u;

export function findQuote(text: string, quote: string): [number, number] | null {
  const q = quote.trim();
  if (!q) return null;
  const at = text.indexOf(q);
  if (at >= 0) return [at, at + q.length];

  const starts: number[] = [];
  const ends: number[] = [];
  let flat = "";
  for (let i = 0; i < text.length; ) {
    const ch = String.fromCodePoint(text.codePointAt(i)!);
    if (!LOOSE.test(ch)) {
      for (let u = 0; u < ch.length; u++) {
        starts.push(i);
        ends.push(i + ch.length);
      }
      flat += ch;
    }
    i += ch.length;
  }
  const fq = [...q].filter((c) => !LOOSE.test(c)).join("");
  if (!fq) return null;
  const k = flat.indexOf(fq);
  if (k < 0) return null;
  return [starts[k], ends[k + fq.length - 1]];
}

export interface Segment {
  text: string;
  marked: boolean;
}

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
