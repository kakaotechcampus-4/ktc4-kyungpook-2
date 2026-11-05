import type { Institution, Insight } from "@/lib/types";

/**
 * 근거를 제공한 기관 — 승인 주체(primarySource)를 맨 앞에, 그 뒤로 근거 기록(evidence)에 나온 기관을 한 번씩 (#351).
 *
 * 전에는 승인 주체 하나만 보여서, 근거 4건 중 3건이 센터여도 학교만 떴다. 승인 주체는 근거를 낸 기관 중에서 정해지지만
 * (source_institution_ids), evidence 는 BE 가 걸러 준 목록이라 승인 주체의 기록이 빠져 있을 수 있다. 그래서 승인
 * 주체는 evidence 와 상관없이 늘 넣는다. evidence 가 비어 오거나 빠져 와도 화면이 죽지 않게 빈 목록으로 본다.
 */
export function evidenceInstitutions(insight: Pick<Insight, "evidence" | "primarySource">): Institution[] {
  const seen = new Map<string, Institution>([[insight.primarySource.id, insight.primarySource]]);
  for (const e of insight.evidence ?? []) {
    if (!seen.has(e.institution.id)) seen.set(e.institution.id, e.institution);
  }
  return [...seen.values()];
}
