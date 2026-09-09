/**
 * 기획서 9절 데이터 모델을 화면이 쓰는 형태로 옮긴 것.
 * 백엔드 DTO 가 확정되면 이 파일이 계약서가 된다.
 */

export type InstitutionType = "school" | "center" | "assistant";

/** Validation Agent 판정 — 기획서 11.3 */
export type ValidationStatus = "PASS" | "REVIEW" | "BLOCK";

/** Matching Agent 결과 상태 */
export type MatchStatus = "confirmed" | "multi" | "unmatched" | "low";

export type Gate1Status = "pending" | "approved" | "rejected";
export type Gate2Status = "pending" | "approved" | "held" | "sent";

/** ConsentScope 로부터 파생된 화면용 상태 */
export type ConsentState = "granted" | "not_granted" | "revoked";

/** 부모 동의 완료 전에는 파이프라인이 한 줄도 돌지 않는다 */
export type ChildStatus = "pending_consent" | "active" | "suspended";

export type ConsentField = "daily_summary" | "weekly_insight";

export interface Institution {
  id: string;
  name: string;
  type: InstitutionType;
  verified: boolean;
}

export interface Child {
  id: string;
  name: string;
  birthDate: string;
  status: ChildStatus;
  institutions: { institution: Institution; consent: ConsentState }[];
}

export interface RawRecord {
  id: string;
  fileName: string;
  type: "관찰일지" | "활동일지" | "특이사항" | "사진";
  capturedAt: string;
  preview: string;
}

/** 확인 필요 큐 한 건 */
export interface MatchingItem {
  id: string;
  record: RawRecord;
  status: Exclude<MatchStatus, "confirmed">;
  confidence: number | null;
  candidates: { childId: string; name: string; group: string }[];
}

/** 재입력 요청 큐 한 건 */
export interface BlockedItem {
  id: string;
  record: RawRecord;
  childName: string | null;
  violationReason: string;
}

/** Gate 1 검토 대기 한 건 */
export interface SummaryItem {
  id: string;
  childId: string;
  childName: string;
  institutionName: string;
  date: string;
  recordType: RawRecord["type"];
  validation: ValidationStatus;
  content: string;
  /** REVIEW 사유가 된 문장 — 본문 안에서 하이라이트한다 */
  flaggedSpan?: string;
  flagReason?: string;
  gate1Status: Gate1Status;
  rejectReason?: string;
  sourceCount: number;
}

/** Child Context 타임라인 한 칸 */
export interface TimelineEntry {
  date: string;
  /** 기록이 없는 날은 그대로 비워 표시한다 — 추정치로 채우지 않는다 */
  entry: {
    recordType: RawRecord["type"];
    validation: ValidationStatus;
    content: string;
    sourceCount: number;
    version: number;
    edited: boolean;
  } | null;
}

export interface EvidenceRef {
  childContextId: string;
  date: string;
  label: string;
  /** 어느 기관 기록인지 — Gate 2 승인자가 확인해야 한다 */
  institution: Institution;
}

export interface Insight {
  id: string;
  childId: string;
  childName: string;
  period: string;
  content: string;
  /** 근거 요약을 최다 제공한 기관 = Gate 2 승인 주체 */
  primarySource: Institution;
  evidence: EvidenceRef[];
  targets: { institution: Institution; consent: ConsentState }[];
  gate2Status: Gate2Status;
}

export interface InboxItem {
  id: string;
  from: Institution;
  childName: string;
  receivedAt: string;
  /** 원본이 아니라 수신자별로 변환된 최소 정보 */
  content: string;
  read: boolean;
}

export interface ChatSource {
  date: string;
  label: string;
}

export interface ChatTurn {
  question: string;
  /** 근거가 없으면 answer 는 null 이고 답변을 생성하지 않는다 */
  answer: string | null;
  sources: ChatSource[];
}

export interface ActivityLog {
  id: string;
  at: string;
  actor: string;
  action: string;
  target: string;
}

export interface ParentActivity {
  id: string;
  at: string;
  text: string;
}
