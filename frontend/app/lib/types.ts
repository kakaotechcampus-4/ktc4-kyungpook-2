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
 * `failed` 는 AI 호출 자체가 실패한 건(네트워크·타임아웃)이라 후보도 근거도 없다.
 */
export type MatchStatus = "auto" | "review" | "multi" | "unmatched" | "failed";

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

/**
 * 반려(reject)는 없다 — BE 는 보류(hold)와 제거(remove)만 받는다 (#178).
 * 보류한 요약은 바로 승인할 수 없다. 보류 해제(O-33)로 검토 대기에 돌린 뒤 승인하거나, 제거한다.
 * 요약할 내용 없음(noContent) 요약은 확인(confirmed)하거나 요약 전으로 되돌린다(reverted) (#244).
 * 목록(O-30)은 pending · hold 만 준다. 나머지는 mock 이 목록에서 빼는 데만 쓴다.
 */
export type Gate1Status = "pending" | "approved" | "hold" | "removed" | "confirmed" | "reverted";
/** BE Gate2InsightResponse.gate2Status. 승인이 곧 보호자 열람이라 "발송 완료"가 따로 없다 */
export type Gate2Status = "pending" | "approved" | "hold";

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

/**
 * 보호자가 설정 › 아이 정보에서 적는 돌봄 정보 (G-13). 현재 이 아이와 연결된 모든 보호자가 조회·수정하고,
 * 기관에는 공유하지 않는다. 복용 시간은 비어 있을 수 있다("").
 */
export interface ChildCareInfo {
  allergies: string[];
  medications: { name: string; time: string }[];
}

export interface Child {
  id: string;
  name: string;
  birthDate: string;
  /** 학교/학년 등 — 보호자 온보딩 화면에 짧게 표시 */
  school?: string;
  status: ChildStatus;
  /** 기관이 아이마다 붙여 쓰는 관리번호 (예: 2026-0031). 없으면 null */
  externalId?: string | null;
  institutions: { institution: Institution; consent: ConsentState }[];
  /**
   * 보호자가 기관 코드로 연결을 요청했고 기관의 수락을 기다리는 기관 (G-10·G-11). 아직 명부에 없어
   * 동의할 수 없으므로 institutions 와 섞지 않는다. 기관 화면 mock 에는 없다.
   */
  linkRequests?: LinkRequest[];
  care?: ChildCareInfo;
}

/** 수락 대기 중인 기관 하나. requestedAt 은 가장 먼저 요청한 시각 (ISO 8601) */
export interface LinkRequest {
  institution: Institution;
  requestedAt: string;
}

/**
 * 기관 아동 명부 한 줄 (GET /institutions/me/children).
 *
 * 서버는 id·name·birthDate·externalId·status·consent 만 준다. 연결된 기관 목록은 다른 기관
 * 정보를 보호자 동의 범위 안에서만 보여줘야 해서, 동의 기능이 생긴 뒤에 온다.
 *
 * **기관 화면은 status 가 아니라 consent 로 판단한다.** status 는 아이 전체 요약이라 어느 기관이든
 * 동의가 있으면 active 다. 학교에 동의한 아이가 우리 명부에 막 올라오면 status 는 active 인데
 * 우리 기관에는 아직 동의 전(not_granted)이다.
 */
export type ChildListItem = Omit<Child, "institutions" | "linkRequests"> &
  Partial<Pick<Child, "institutions">> & {
    /** 우리 기관에 대한 동의 상태 */
    consent: ConsentState;
  };

/** 내 기관 정보 (O-01). code 는 보호자에게 알려 주는 기관 코드 8자 — 화면은 4-4 로 나눠 보여준다 */
export interface MyInstitution extends Institution {
  code: string;
}

/** 기관이 보는 연결 요청 하나 (O-60). 같은 아이에 보호자 여럿이 요청했으면 한 항목이다 */
export interface PendingLinkRequest {
  child: { id: string; name: string; birthDate: string };
  /** 요청한 보호자들. 먼저 요청한 순서. 이름은 카카오 닉네임이라 없을 수 있다 */
  requesters: { name: string | null }[];
  /** 가장 먼저 요청한 시각 (ISO 8601) */
  requestedAt: string;
}

/** 아이 등록 응답 (POST /institutions/me/children). 등록 직후라 status 는 항상 pending_consent 다 */
export type RegisteredChild = Pick<Child, "id" | "name" | "birthDate" | "status"> & {
  externalId: string | null;
};

/**
 * 보호자 초대 코드 (api-spec O-11 의 invitation · O-15 응답).
 *
 * 10분짜리 일회용이다. code 는 하이픈 없는 8자라 화면에서 4-4 로 나눠 보여준다.
 */
export interface IssuedInvitation {
  id: string;
  code: string;
  /** ISO 8601 (오프셋 포함). 화면은 브라우저 시계와 비교해 카운트다운한다 */
  expiresAt: string;
}

/** 아직 쓸 수 있는 초대 코드 (api-spec O-16) */
export interface LiveInvitation extends IssuedInvitation {
  issuedAt: string;
}

/** 아이와 연결된 보호자 (api-spec O-18) */
export interface ChildGuardian {
  userId: string;
  /** 카카오 닉네임. 동의하지 않았으면 null */
  name: string | null;
  linkedAt: string;
  /** true 인 보호자만 우리 기관이 연결을 끊을 수 있다 (O-19) */
  linkedByThisOrganization: boolean;
}

/**
 * 초대 코드 수락 결과 (api-spec G-04).
 *
 * 수락은 아동 상태를 바꾸지 않는다. childStatus 는 아이의 실제 현재 상태다.
 */
export interface AcceptedInvitation {
  childId: string;
  childName: string;
  organizationName: string;
  /** 코드를 발급한 기관. 수락 뒤 이 기관에 대한 동의 미리보기(G-02)를 부를 때 쓴다 */
  organizationId: string;
  childStatus: ChildStatus;
}

/**
 * P-02 확인 · 동의 화면이 한 번에 그리는 정보 (api-spec G-02).
 *
 * 스펙 예시에는 child.id 가 없지만 동의(G-41)와 연결 해제(G-05) 호출에 필요해서 받는다.
 */
export interface ConsentPreview {
  child: { id: string; name: string; birthDate: string };
  institution: Institution;
  /** 증빙서류. 없으면 null — 화면에서 버튼을 숨긴다 */
  documentUrl: string | null;
  /** 화면에 보여 줄 설명 문구. 저장하지 않는다 */
  sharedFields: string[];
  notSharedFields: string[];
  /** 동의할 때 G-41 에 그대로 돌려보낼 항목 코드. 저장되는 것은 이 값이다 */
  allowedFields: ConsentField[];
}

/** 동의 부여 · 철회 결과 (api-spec G-41). 바뀐 뒤의 상태다 */
export interface ConsentResult {
  childStatus: ChildStatus;
  /** 이 기관에 대한 동의 상태 */
  consent: ConsentState;
  /** 이 기관에 지금 공유되는 항목. granted 가 아니면 빈 배열 */
  allowedFields: ConsentField[];
}

export interface RawRecord {
  id: string;
  fileName: string;
  type: "관찰일지" | "활동일지" | "특이사항";
  capturedAt: string;
  preview: string;
}

/**
 * 확인 필요 큐 한 건 (api-spec O-22).
 *
 * `null` 이거나 없을 수 있는 필드는 BE(#35)가 아직 채우지 못하는 값이다.
 * 유형(`type`)·반(`group`)·미매칭 이유·표지 이름은 보내지 않고, 원문·날짜·명부가
 * 비어 있으면 `null` 이 온다. 화면은 값이 없으면 그 칸을 숨긴다.
 */
export interface MatchingItem {
  id: string;
  record: {
    id: string;
    fileName: string;
    type?: RawRecord["type"] | null;
    capturedAt: string | null;
    /** 앞 60자만 자른 목록용 미리보기. 뒤쪽 아이 이름·근거는 잘린다 */
    preview: string | null;
    /**
     * 자르지 않은 전체 본문 (#112). 아이를 고를 때는 이걸 보여준다.
     * `evidence` 좌표도 preview 가 아니라 이 값 기준이다.
     */
    fullContent: string | null;
  };
  status: Exclude<MatchStatus, "auto">;
  /**
   * AI 가 지목한 아이. **`review` 의 추천 아이는 여기로만 온다** — AI 계약상
   * `candidates` 는 `multi` 일 때만 채워진다(AI/matching/graph.py). 이름·생년월일은
   * 오지 않으니 화면이 명부에서 찾아 붙인다.
   */
  matchedChildId: string | null;
  /**
   * `multi` 의 후보들. 그 밖의 상태에서는 빈 배열이다.
   * 동명이인이면 후보가 둘 이상 남는다(AI 의 SPLIT_SAME_NAME_CANDIDATES).
   * 이름만으로는 구별이 안 되므로 **생년월일이 반드시 함께 와야 한다.**
   * 명부에서 삭제된 아이는 서버가 이름·생년월일 없이 id 만 보낸다.
   */
  candidates: {
    childId: string;
    name: string | null;
    group?: string | null;
    birthDate: string | null;
  }[];
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

/** 매칭 확인 화면에서 교사가 내린 결정 (api-spec O-23) */
export type MatchResolution =
  /** 고른 아이로 확정 */
  | { action: "assign"; childId: string }
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

/**
 * 파일 하나의 업로드 결과. 여러 파일을 올리면 일부만 실패할 수 있어 파일마다 따로 돌려준다.
 * 실패한 건 서버에 없으므로 원본 File 을 들고 있다가 그것만 다시 올린다.
 */
export type UploadResult =
  | { ok: true; file: File; progress: FileProgress }
  | { ok: false; file: File; reason: UploadFailReason };

/**
 * invalid   → 허용되지 않는 형식 (400)
 * too_large → 용량 초과 (413)
 * temporary → 저장 실패 · 네트워크 오류 등. 같은 파일을 다시 올리면 될 수 있다
 */
export type UploadFailReason = "invalid" | "too_large" | "temporary";

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

/**
 * 수정 요청(BLOCK) 큐 한 건 (api-spec O-24).
 *
 * `record` 는 확인 필요 큐와 같은 모양이다(BE RecordResponse). 유형(`type`)은 BE 가 아직
 * 보내지 않고, 날짜는 `YYYY-MM-DD` 로 온다. 비어 있으면 화면이 그 칸을 숨긴다.
 */
export interface BlockedItem {
  id: string;
  /** 검증 결과가 붙은 일지 id. 화면에서는 쓰지 않는다 */
  journalEntryId?: number;
  record: {
    id: string;
    fileName: string;
    type?: RawRecord["type"] | null;
    capturedAt: string | null;
    /** 앞 60자만 자른 목록용 미리보기 */
    preview: string | null;
    /** 자르지 않은 전체 본문. 막힌 문장이 60자 뒤에 있을 수 있어 이걸 보여준다 */
    fullContent: string | null;
  };
  /** 매칭 전에 막혔으면 아이를 모른다 */
  childName: string | null;
  /** 화면에 그대로 띄우는 한국어 문장. 사유가 여럿이면 " / " 로 이어져 온다 */
  violationReason: string;
  /** AI 가 보낸 위반 유형 원본(`진단명` · `개인정보표현` 등). 화면에서는 쓰지 않는다 */
  violationCode?: string[];
}

/** 요약 문장 하나. index 는 결정(O-31) 때 그대로 돌려보낸다 */
export interface SummaryClaim {
  index: number;
  text: string;
}

/**
 * Gate 1 검토 대기 · 보류함 한 건 (BE Gate1SummaryResponse, api-spec O-30).
 * id 는 문자열로, status(GATE1_PENDING · HOLD)는 gate1Status 로 바꿔 받는다 (lib/api.ts).
 */
export interface SummaryItem {
  id: string;
  childId: string;
  childName: string;
  /** 일지 날짜 YYYY-MM-DD */
  entryDate: string;
  /** 같은 묶음을 몇 번째 요약했는지 */
  revision: number;
  gate1Status: Gate1Status;
  /** 결정할 때 그대로 돌려보내는 판 표시. 그 사이 요약이 바뀌었으면 409 SUMMARY_CHANGED */
  version: string;
  /** 같은 날 새 기록이 반영되는 중이라 곧 요약이 바뀐다 — 고치기·승인을 막는다 */
  processing: boolean;
  content: string;
  /** 빈 배열이면 문장을 못 읽은 요약 — 문장 편집 없이 그대로 승인하거나 제거만 된다 */
  claims: SummaryClaim[];
  /**
   * 그날 기록에 요약할 내용이 없다는 결과 (#244). content 는 "", claims 는 [] 이고
   * uncoveredEntries 가 이 요약에 묶인 기록 원본 전체다. 결정은 confirm · revert 만 받는다.
   */
  noContent: boolean;
  /** 본문에 다른 아이 이름이 남아 있을 수 있다. 막지 않고 경고만 한다 */
  needsReview: boolean;
  reviewReasons: string[];
  /** 요약에 안 들어간 일지. 승인하면 이 일지도 함께 끝난다 */
  uncoveredEntries: { journalEntryId: string; content: string }[];
  /** 요약에 들어간 원본 중 검증에서 REVIEW 를 받은 일지와 사유 원본 */
  reviewFlags: { journalEntryId: string; issueTypes: string[] }[];
}

/**
 * 묶음 카드 라벨 (api-spec O-34). 위에서부터 처음 맞는 것이다 — 선생님이 할 일이 먼저다.
 * needs_fix 수정 요청 있음 · ready 요약 대기 · summarizing 요약 중이거나 [요약] 눌러 둠 ·
 * validating 검증 중만 · admin_check 시스템 오류만 남음
 */
export type SummaryGroupStatus = "needs_fix" | "ready" | "summarizing" | "validating" | "admin_check";

/**
 * 오늘의 업무 묶음 카드 한 장 = 아이 한 명의 하루치 기록 (BE SummaryGroupCard).
 * 요약 전 묶음만 온다 — 요약이 만들어지면 1차 검토(O-30)로 옮겨 가고 카드는 빠진다.
 */
export interface SummaryGroup {
  /** BE 는 숫자. lib/api.ts 가 문자열로 바꾼다 */
  childId: string;
  childName: string;
  /** 같은 이름의 아이를 구분하려고 함께 보인다 */
  birthdate: string | null;
  /** 일지 날짜 YYYY-MM-DD. [요약](O-32)에 그대로 보낸다 */
  entryDate: string;
  status: SummaryGroupStatus;
  counts: {
    validating: number;
    needsFix: number;
    ready: number;
    summarizing: number;
    adminCheck: number;
  };
  /** [요약]을 누를 수 있음. 버튼은 늘 두고 false 면 막는다 */
  canSummarize: boolean;
  /** [요약]을 눌렀고 아직 요약이 끝나지 않음 */
  summaryRequested: boolean;
  /** 눌렀지만 시작 전일 때, 요약이 기다리는 같은 날 매칭·검증 중 기록 수 */
  waitingCount: number;
  /** 이전 요약이 1차 검토에 있음 — 다시 요약하면 그 요약이 새 판으로 바뀐다 */
  previousSummaryInReview: boolean;
}

export interface SummaryGroupBoard {
  /** 아이가 아직 정해지지 않아 카드에 넣을 수 없는 기록. 자동 매칭 중 · 확인 필요 큐 */
  matching: { inProgress: number; needsReview: number };
  /** 오래된 날짜부터 */
  groups: SummaryGroup[];
}

/**
 * Gate 1 승인 때 보내는 문장 카드 (api-spec O-31). 원래 문장은 모두 한 번씩, 배열 순서가 최종 순서다.
 * KEEP · DELETE 는 index 만, EDIT 는 index 와 고친 글, ADD 는 새 글만 보낸다.
 */
export type Gate1Card =
  | { index: number; action: "KEEP" | "DELETE" }
  | { index: number; action: "EDIT"; text: string }
  | { action: "ADD"; text: string };

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
  /**
   * 공유 전에 꼭 봐야 하는 글인지. BE 는 지금 항상 true 를 준다 — 본문에 다른 아이 이름이
   * 없다는 보장이 없고, Gate 1 에서 교사가 고친 문장이 검증 없이 근거로 섞일 수 있다.
   */
  needsReview: boolean;
  /** 승인 주체. 근거를 낸 기관 중 기관 종류 우선순위(학교 > 센터 > 활동지원사)로 정한다 */
  primarySource: Institution;
  evidence: EvidenceRef[];
  /** 읽기 전용 참고. 수신 기관은 기관이 고르지 않고 승인 뒤 보호자가 정한다 (#206) */
  targets: { institution: Institution; consent: ConsentState }[];
  gate2Status: Gate2Status;
}

/**
 * 기관 수신함 목록 한 줄 (O-50). 다른 기관이 Gate 2 에서 승인해 우리 기관에 열린 인사이트다.
 * **본문이 없다** — 단건(O-51)을 열어야 보이고, 그때 우리 기관의 처음 열람 시각이 남는다.
 * 같이 받은 다른 기관은 오지 않는다(아이가 어디에 또 다니는지는 최소 정보를 넘는다).
 */
export interface InboxItem {
  /** 인사이트 id. 단건 경로에 그대로 쓴다 */
  id: string;
  childId: string;
  childName: string;
  /** 우리 기관이 붙인 관리번호. 이름이 같은 아이를 가른다 */
  externalId: string;
  /** 이 글을 Gate 2 에서 승인한 기관 */
  from: Institution;
  /** "2026년 8월 3주차" 같은 문구 */
  period: string;
  /** 우리 기관이 볼 수 있게 된 시각 (ISO) */
  receivedAt: string;
  /** 우리 기관에서 처음 열어 본 시각 (ISO). 아직 안 열었으면 null — 키는 항상 온다 */
  readAt: string | null;
}

/** 기관 수신함 단건 (O-51). 본문은 보호자에게 열린 글과 같다 — 수신자별 변환은 아직 없다 */
export interface InboxDetail extends InboxItem {
  content: string;
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

/**
 * 학부모 홈/타임라인에 뜨는 일지 한 건 (G-20·G-21) — 교사가 1차 검토에서 승인한 요약 하나.
 * 요약은 하루 단위라 시각이 없다. 목록은 content 를 2줄로 자르고, 상세는 전부 보여 준다.
 */
export interface JournalEntry {
  id: string;
  childId: string;
  institution: Institution;
  /** 관찰 날짜 yyyy-MM-dd */
  date: string;
  /** 승인된 지 24시간이 안 됐다. 읽었는지와는 무관하다 */
  isNew: boolean;
  /** 교사가 승인한 글 */
  content: string;
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
