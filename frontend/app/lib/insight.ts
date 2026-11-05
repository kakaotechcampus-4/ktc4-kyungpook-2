import type { Institution, Insight } from "@/lib/types";

/**
 * 근거를 제공한 기관 — 근거 기록(evidence)에 나온 기관을 한 번씩, 처음 나온 순서대로 (#351).
 *
 * 전에는 승인 주체(primarySource) 하나만 보여서, 근거 4건 중 3건이 센터여도 학교만 떴다. 승인 주체는 "누가 승인하나"
 * 이고 근거 기관은 "누구 기록에서 나왔나" 라 다르다. 근거가 비어 있는 예전 데이터면 승인 주체로 대신한다.
 */
export function evidenceInstitutions(insight: Pick<Insight, "evidence" | "primarySource">): Institution[] {
  const seen = new Map<string, Institution>();
  for (const e of insight.evidence) {
    if (!seen.has(e.institution.id)) seen.set(e.institution.id, e.institution);
  }
  return seen.size > 0 ? [...seen.values()] : [insight.primarySource];
}
