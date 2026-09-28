/**
 * 기획서 9절 데이터 모델을 화면이 쓰는 형태로 옮긴 것.
 * 백엔드 DTO 가 확정되면 이 파일이 계약서가 된다.
 */

export type InstitutionType = "school" | "center" | "assistant";

/** Validation Agent 판정 — 기획서 11.3 */
export type ValidationStatus = "PASS" | "REVIEW" | "BLOCK";

/**
 * Matching Agent 결과 상태. AI 쪽 이름(`AI/matching/nodes.py` 의 decide)과 맞춘다.
 * `auto` 는 자동 확정이라 확인 큐에 오지 않는다.
 */
export type MatchStatus = "auto" | "review" | "multi" | "unmatched";

/** multi 로 내려온 이유 — 둘은 교사가 할 일이 서로 다르다 */
export type MultiReason = "co_mention" | "ambiguous_identity";

/** unmatched 로 내려온 이유 — 미등록 아동이면 등록 화면으로 보내야 한다 */
export type UnmatchedReason = "no_anchor" | "not_in_roster";

/**
 * 본문에서 판정 근거가 된 구간.
 * 인덱스는 **유니코드 코드포인트** 기준이다(Python str 인덱스, `AI/matching/schemas.py`).
 */
export interface EvidenceSpan {
  start: number;
  end: number;
}

/**
 * 이 기록을 왜 이 아이로 봤는지 — Gate 1 에 한 줄로 보여준다.
 * cover   → "파일 표지: 이하은"
 * body    → 본문에 "이하은" 등장
 * teacher → 선생님이 직접 지정 (매칭 확인 화면 또는 Gate 1 에서 바꾼 경우)
 */
export interface MatchBasis {
  source: "cover" | "body" | "teacher";
  /** 근거가 된 이름. 표지·본문에 적힌 그대로다 (명부의 이름과 다를 수 있다) */
  name: string;
}

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

/** 보호자가 온보딩 중 직접 입력하는 돌봄 정보 — 기관마다 다르게 공유된다 */
export interface ChildCareInfo {
  welfareCard: boolean;
  allergies: string[];
  medications: { name: string; time: string }[];
  weeklySchedule: { day: string; note: string }[];
}

export interface Child {
  id: string;
  name: string;
  birthDate: string;
  /** 학교/학년 등 — 보호자 온보딩 화면에 짧게 표시 */
  school?: string;
  status: ChildStatus;
  institutions: { institution: Institution; consent: ConsentState }[];
  care?: ChildCareInfo;
}

/**
 * 대기 중인 보호자–아이 연결 요청 (api-spec G-01).
 *
 * 기관이 아이를 등록하면 서버가 만들고, 보호자가 카카오 로그인 뒤 이 목록에서
 * 확인·동의하면 사라진다. 초대코드를 대신하는 최초 연결 경로다.
 */
export interface PendingLink {
  child: { id: string; name: string; birthDate: string };
  institution: Institution;
  /** ISO 8601. 화면은 앞 10자리만 잘라 날짜로 보여준다. */
  requestedAt: string;
}

/**
 * P-02 확인 · 동의 화면이 한 번에 그리는 정보 (api-spec G-02).
 *
 * 스펙 예시에는 child.id 가 없지만 동의(G-41)와 반려(G-03) 호출에 필요해서 받는다.
 */
export interface ConsentPreview {
  child: { id: string; name: string; birthDate: string };
  institution: Institution;
  /** 증빙서류. 없으면 null — 화면에서 버튼을 숨긴다 */
  documentUrl: string | null;
  sharedFields: string[];
  notSharedFields: string[];
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
  status: Exclude<MatchStatus, "auto">;
  /**
   * 동명이인이면 후보가 둘 이상 남는다(AI 의 SPLIT_SAME_NAME_CANDIDATES).
   * 이름만으로는 구별이 안 되므로 **생년월일이 반드시 함께 와야 한다.**
   */
  candidates: { childId: string; name: string; group: string; birthDate: string }[];
  /** 본문에서 이 판정의 근거가 된 구간. 화면에서 하이라이트한다. */
  evidence: EvidenceSpan[];
  multiReason?: MultiReason | null;
  unmatchedReason?: UnmatchedReason | null;
  /** 표지에 적힌 이름과 본문 판정이 어긋남 */
  hintMismatch?: boolean;
  /** 표지에 적혀 있던 이름 */
  hintName?: string | null;
}

/*
 * confidence 는 일부러 두지 않는다.
 *
 * 모델 점수는 같은 입력에도 0.72~0.98 로 흔들리고(AI/matching/config.py 참고),
 * AI 쪽도 자동 확정 판단에서 이 값을 뺐다(AUTO_GATE = "structural").
 * 화면에 숫자를 띄우면 교사가 그 숫자를 근거로 삼게 되므로 상태 문구로만 표현한다.
 */

/** 매칭 확인 화면에서 교사가 내린 결정 */
export type MatchResolution =
  | { action: "confirm"; childId: string }
  /** 명부에 있는 아이가 아니다 — 파이프라인에서 뺀다 */
  | { action: "not_ours" };

/**
 * 파일 하나의 처리 현황.
 *
 * 업로드는 즉시 응답하고 처리는 백그라운드에서 돈다. 파일 하나에서 기록이 여러 건
 * 나오고, 건마다 도달한 단계가 다르므로 건별 위치를 받아 화면이 집계한다.
 */
export interface FileProgress {
  rawRecordId: string;
  fileName: string;
  uploadedAt: string;
  /** 파일에서 기록을 아직 떼어내지 못했으면 비어 있다 (기록 등록 단계) */
  entries: EntryProgress[];
}

export interface EntryProgress {
  id: string;
  /**
   * PIPELINE_STAGES 의 인덱스.
   * 1차 검토까지 승인돼 파이프라인을 빠져나간 건은 PIPELINE_STAGES.length 이고 state 는 done 이다.
   */
  stageIndex: number;
  /**
   * running → 자동으로 진행 중
   * waiting → 사람이 골라야 진행 (매칭 확인 · 수정 요청 · 1차 검토)
   * failed  → 시스템 오류. 재시도하면 된다
   */
  state: "running" | "waiting" | "failed" | "done";
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
  /** 매칭 근거. 옛 데이터에는 없을 수 있어 선택으로 둔다 */
  matchBasis?: MatchBasis | null;
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
  /** 이 활동이 특정 일지에서 비롯됐으면 상세로 이동할 수 있게 연결한다 */
  journalId?: string;
}

/** 학부모 홈/타임라인에 뜨는 일지 한 건 */
export interface JournalEntry {
  id: string;
  childId: string;
  institution: Institution;
  date: string;
  time: string;
  isNew: boolean;
  tag: string;
  summary: string;
  detail: string;
  institutionNote?: string;
  photoCount?: number;
  /** "이 내용이 이상해요" — 보호자가 기관에 재확인을 요청했는지 */
  flagged?: boolean;
}

export interface CareReportTrendPoint {
  label: string;
  value: number;
}

export interface CareReportPattern {
  text: string;
  evidenceIds: string[];
}

export interface CareReport {
  period: "weekly" | "monthly";
  rangeLabel: string;
  summary: string;
  trendTitle: string;
  trend: CareReportTrendPoint[];
  trendInsight: string;
  trendEvidenceIds: string[];
  patterns: CareReportPattern[];
  tips: string[];
}

/** 설정 › 기관 요청사항 한 건 */
export interface InstitutionRequestItem {
  id: string;
  institution: Institution;
  status: "confirmed" | "needs_check";
  items: string[];
}
