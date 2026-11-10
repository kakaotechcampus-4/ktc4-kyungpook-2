export type InstitutionType = "school" | "center" | "assistant";

export type ValidationStatus = "PASS" | "REVIEW" | "BLOCK";

export type MatchStatus = "auto" | "review" | "multi" | "unmatched" | "failed";

export type MultiReason = "co_mention" | "ambiguous_identity";

export interface EvidenceSpan {
  start: number;
  end: number;
}

export interface MatchBasis {
  source: "cover" | "body" | "teacher";
  name: string;
}

export type Gate1Status = "pending" | "approved" | "hold" | "removed" | "confirmed" | "reverted";
export type Gate2Status = "pending" | "approved" | "hold";

export type ConsentState = "granted" | "not_granted" | "revoked";

export type ChildStatus = "pending_consent" | "active" | "suspended";

export type ConsentField = "daily_summary" | "weekly_insight";

export interface Institution {
  id: string;
  name: string;
  type: InstitutionType;
  verified: boolean;
}

export interface ChildCareInfo {
  allergies: string[];
  medications: { name: string; time: string }[];
}

export interface Child {
  id: string;
  name: string;
  birthDate: string;
  school?: string;
  status: ChildStatus;
  externalId?: string | null;
  institutions: { institution: Institution; consent: ConsentState }[];
  linkRequests?: LinkRequest[];
  care?: ChildCareInfo;
  hasUnreadInsight?: boolean;
}

export interface LinkRequest {
  institution: Institution;
  requestedAt: string;
}

export type ChildListItem = Pick<Child, "id" | "name" | "birthDate" | "externalId" | "status"> & {
  consent: ConsentState;
};

export interface MyInstitution extends Institution {
  code: string;
}

export interface PendingLinkRequest {
  child: { id: string; name: string; birthDate: string };
  requesterCount: number;
  requestedAt: string;
}

export type RegisteredChild = Pick<Child, "id" | "name" | "birthDate" | "status"> & {
  externalId: string | null;
};

export interface IssuedInvitation {
  id: string;
  code: string;
  expiresAt: string;
}

export interface LiveInvitation extends IssuedInvitation {
  issuedAt: string;
}

export interface ChildGuardian {
  userId: string;
  linkedAt: string;
  linkedByThisOrganization: boolean;
  invitationCode: string | null;
}

export interface AcceptedInvitation {
  childId: string;
  childName: string;
  organizationName: string;
  organizationId: string;
  childStatus: ChildStatus;
}

export interface ConsentPreview {
  child: { id: string; name: string; birthDate: string };
  institution: Institution;
  documentUrl: string | null;
  allowedFields: ConsentField[];
}

export interface ConsentResult {
  childStatus: ChildStatus;
  consent: ConsentState;
  allowedFields: ConsentField[];
}

export interface RawRecord {
  id: string;
  fileName: string;
  type: "관찰일지" | "활동일지" | "특이사항";
  capturedAt: string;
  preview: string;
}

export interface MatchingItem {
  id: string;
  record: {
    id: string;
    fileName: string;
    type?: RawRecord["type"] | null;
    capturedAt: string | null;
    preview: string | null;
    fullContent: string | null;
  };
  status: Exclude<MatchStatus, "auto">;
  matchedChildId: string | null;
  candidates: {
    childId: string;
    name: string | null;
    group?: string | null;
    birthDate: string | null;
  }[];
  evidence: EvidenceSpan[];
  multiReason?: MultiReason | null;
  hintMismatch?: boolean;
  hintName?: string | null;
}

export type MatchResolution =
  | { action: "assign"; childId: string }
  | { action: "not_ours" };

export interface FileProgress {
  rawRecordId: string;
  fileName: string;
  uploadedAt: string;
  entries: EntryProgress[];
}

export type UploadResult =
  | { ok: true; file: File; progress: FileProgress }
  | { ok: false; file: File; reason: UploadFailReason };

export type UploadFailReason =
  | "invalid"
  | "empty"
  | "too_large"
  | "too_many_entries"
  | "queue_full"
  | "server_busy"
  | "temporary";

export interface EntryProgress {
  id: string;
  stageIndex: number;
  state: "running" | "waiting" | "failed" | "done";
}

export interface BlockedItem {
  id: string;
  journalEntryId?: number;
  record: {
    id: string;
    fileName: string;
    type?: RawRecord["type"] | null;
    capturedAt: string | null;
    preview: string | null;
    fullContent: string | null;
  };
  childName: string | null;
  violationReason: string;
  violationCode?: string[];
}

export interface SummaryClaim {
  index: number;
  text: string;
  evidence?: ClaimEvidence[];
}

export interface ClaimEvidence {
  journalEntryId: string;
  quote: string;
}

export interface SummaryItem {
  id: string;
  childId: string;
  childName: string;
  entryDate: string;
  revision: number;
  gate1Status: Gate1Status;
  version: string;
  processing: boolean;
  content: string;
  claims: SummaryClaim[];
  noContent: boolean;
  needsReview: boolean;
  reviewReasons: string[];
  uncoveredEntries: { journalEntryId: string; content: string }[];
  entries: { journalEntryId: string; content: string }[];
  reviewFlags: { journalEntryId: string; issueTypes: string[]; reasons: string[]; claimIndexes: number[] }[];
}

export type Gate1Card =
  | { index: number; action: "KEEP" | "DELETE" }
  | { index: number; action: "EDIT"; text: string }
  | { action: "ADD"; text: string };

export interface TimelineEntry {
  id: string;
  date: string;
  content: string;
  edited: boolean;
  sourceCount: number;
}

export interface EvidenceRef {
  childContextId: string;
  date: string;
  label: string;
  institution: Institution;
  lookedUp?: boolean;
}

export interface Insight {
  id: string;
  childId: string;
  childName: string;
  period: string;
  content: string;
  needsReview: boolean;
  teacherWritten?: boolean;
  primarySource: Institution;
  evidence: EvidenceRef[];
  targets: { institution: Institution; consent: ConsentState }[];
  gate2Status: Gate2Status;
}

export interface InboxItem {
  id: string;
  childId: string;
  childName: string;
  externalId: string | null;
  from: Institution;
  period: string;
  receivedAt: string;
  readAt: string | null;
}

export interface InboxDetail extends InboxItem {
  content: string;
}

export interface ParentActivity {
  id: string;
  kind: "journal" | "insight";
  at: string;
  text: string;
  journalId?: string | null;
  insightId?: string | null;
}

export interface InstitutionShare {
  insightId: string;
  period: string;
  from: Institution | null;
  grantedAt: string;
  visible: boolean;
  hiddenAt: string | null;
}

export interface InstitutionShares {
  institution: Institution;
  shares: InstitutionShare[];
}

export interface GuardianInsight {
  id: string;
  childId: string;
  childName: string | null;
  period: string;
  periodFrom: string;
  periodTo: string;
  content: string | null;
  from: Institution | null;
  sharedWith: Institution[];
  approvedAt: string;
  readAt: string | null;
  evidence: GuardianInsightEvidence[];
  kind?: "insight" | "quiet";
  quietReason?: "NOTHING_NOTABLE" | "NOTHING_NEW" | null;
}

export interface GuardianInsightEvidence {
  journalEntryId: string;
  date: string;
  institution: Institution;
  lookedUp: boolean;
  sentences: GuardianInsightSentence[];
}

export interface GuardianInsightSentence {
  text: string;
  role: "상황" | "대처";
}

export interface JournalEntry {
  id: string;
  childId: string;
  institution: Institution;
  date: string;
  isNew: boolean;
  content: string;
}

export interface JournalEntryDetail extends JournalEntry {
  prevId?: string | null;
  nextId?: string | null;
  sameDay?: JournalEntry[];
}

export interface SentChecklistTarget {
  child: { id: string; name: string | null };
  onRoster: boolean;
  confirmedAt: string | null;
}

export interface SentChecklist {
  id: string;
  items: string[];
  sentAt: string;
  total: number;
  confirmed: number;
  targets: SentChecklistTarget[];
}

export interface SentChecklistPage {
  checklists: SentChecklist[];
  nextBefore: string | null;
}

export interface ChecklistItem {
  id: string;
  institution: Institution;
  status: "confirmed" | "needs_check";
  items: string[];
  sentAt: string;
  confirmedAt: string | null;
}

export interface PipelineTurn {
  confirm: number;
  blocked: number;
  review: number;
  approve: number;
  requests: number;
}

export type PipelineStep = 0 | 1 | 2 | 3 | 4 | 5;

export type PipelineWho = "me" | "ai" | "wait" | "other" | "done";

export type PipelineStatus =
  | "matching"
  | "match_review"
  | "validating"
  | "blocked"
  | "system_error"
  | "waiting_same_date"
  | "summarizing"
  | "summary_failed"
  | "review"
  | "review_held"
  | "regenerating"
  | "waiting_week_end"
  | "queued"
  | "running"
  | "retrying"
  | "not_consented"
  | "nothing_new"
  | "review_here"
  | "insight_held"
  | "review_elsewhere"
  | "approved";

export interface PipelineTarget {
  type: "matching" | "validation" | "summary" | "summary_failed" | "insight";
  id?: string;
}

export interface PipelineRow {
  kind: "entry" | "week";
  childId: string | null;
  childName: string | null;
  date: string | null;
  week: { from: string; to: string; label: string } | null;
  step: PipelineStep;
  who: PipelineWho;
  status: PipelineStatus;
  detail: {
    count?: number;
    kinds?: string[];
    reasons?: string[];
    noContent?: boolean;
    approverName?: string;
  } | null;
  target: PipelineTarget | null;
}

export interface Pipeline {
  turn: PipelineTurn;
  rows: PipelineRow[];
}

export interface AiActivity {
  split: number;
  matched: number;
  validated: number;
  summarized: number;
  insights: number;
}

export interface AiStatus {
  available: boolean;
  since: string | null;
}

export interface AiProgressItem {
  kind: "entry" | "week";
  childId: string | null;
  date: string | null;
  weekFrom: string | null;
  agent: string;
  node: string;
  elapsedSec: number;
}

export interface AiProgress {
  enabled: boolean;
  items: AiProgressItem[];
}

export interface FailedSummary {
  childId: string;
  childName: string;
  entryDate: string;
  entries: { journalEntryId: string; content: string }[];
  reason: string;
}
