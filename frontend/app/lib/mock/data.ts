import type {
  ActivityLog,
  BlockedItem,
  Child,
  ChildGuardian,
  ChatTurn,
  EntryProgress,
  EvidenceSpan,
  FileProgress,
  GuardianInsight,
  InboxDetail,
  Insight,
  Institution,
  InstitutionRequestItem,
  InstitutionType,
  JournalEntry,
  LiveInvitation,
  MatchingItem,
  ParentActivity,
  PendingLinkRequest,
  SummaryGroup,
  SummaryGroupBoard,
  SummaryItem,
  TimelineEntry,
} from "@/lib/types";

/* 기관 ─────────────────────────────────────────────── */

export const CENTER: Institution = {
  id: "inst_center_01",
  name: "OO주간보호센터",
  type: "center",
  verified: true,
};
export const SCHOOL: Institution = {
  id: "inst_school_01",
  name: "OO초등학교",
  type: "school",
  verified: true,
};
export const ASSISTANT: Institution = {
  id: "inst_asst_01",
  name: "활동지원사 이OO",
  type: "assistant",
  verified: true,
};
export const SPEECH: Institution = {
  id: "inst_speech_01",
  name: "OO언어치료센터",
  type: "center",
  verified: true,
};
/** "기관 직접 추가"(초대코드 없이 보호자가 기관 코드로 연결) 데모용 */
export const ART_ACADEMY: Institution = {
  id: "inst_art_01",
  name: "OO미술학원",
  type: "center",
  verified: true,
};

/**
 * 기관 유형별로 실제 공유/비공유되는 항목 — 보호자 동의·권한 관리 화면이
 * 이 값을 그대로 렌더링한다. "공유되지 않는 정보"를 항상 함께 보여줘서
 * "동의하면 다 보이는 거 아닌가" 하는 오해를 막는다.
 */
export const INSTITUTION_SHARE_FIELDS: Record<
  InstitutionType,
  { shared: string[]; notShared: string[] }
> = {
  school: {
    shared: ["출결 시간", "활동 요약", "알레르기 정보"],
    notShared: ["사진과 영상", "의료 기록"],
  },
  center: {
    shared: ["관찰 요약", "변화 추이", "집에서 해본 방법"],
    notShared: ["복지카드 정보"],
  },
  assistant: {
    shared: ["하원 시간", "이동 경로", "필요한 지원 방법"],
    notShared: ["학습 기록"],
  },
};

/** 로그인한 교사가 소속된 기관 */
export const MY_INSTITUTION = CENTER;
/** 우리 기관 코드 — 보호자에게 알려 주면 보호자가 이 코드로 아이 연결을 요청한다 */
export const MY_INSTITUTION_CODE = "K7P3QX9M";
export const ME = { name: "박지현", role: "선생님" };

/* 아동 ─────────────────────────────────────────────── */

export const CHILDREN: Child[] = [
  {
    id: "child_1023",
    name: "김OO",
    birthDate: "2019-03-12",
    school: "OO초등학교 1학년",
    status: "active",
    institutions: [
      { institution: CENTER, consent: "granted" },
      { institution: SCHOOL, consent: "granted" },
      { institution: ASSISTANT, consent: "granted" },
      { institution: SPEECH, consent: "not_granted" },
    ],
    care: {
      allergies: ["갑각류(새우)"],
      medications: [{ name: "알레르기 약", time: "점심 식후" }],
    },
  },
  {
    id: "child_1041",
    name: "이OO",
    birthDate: "2018-07-02",
    status: "active",
    institutions: [
      { institution: CENTER, consent: "granted" },
      { institution: SCHOOL, consent: "revoked" },
    ],
  },
  {
    id: "child_1077",
    name: "박OO",
    birthDate: "2020-01-25",
    // 부모 동의 대기 — 업로드·파이프라인 전체가 막힌 상태
    status: "pending_consent",
    institutions: [{ institution: CENTER, consent: "not_granted" }],
  },
  {
    // 동명이인 데모용 — child_1023 과 이름이 같다. 생년월일로만 구별된다 (확인 필요 큐 mq_01)
    id: "child_1088",
    name: "김OO",
    birthDate: "2021-09-02",
    status: "active",
    institutions: [{ institution: CENTER, consent: "granted" }],
  },
  {
    // 다자녀 보호자 데모용 — child_1023 과 같은 보호자의 둘째 아이
    id: "child_2044",
    name: "최OO",
    birthDate: "2021-05-30",
    school: "OO어린이집",
    status: "active",
    institutions: [{ institution: CENTER, consent: "granted" }],
    care: { allergies: [], medications: [] },
  },
  {
    // 기관 연결 요청 데모용 — 학교에만 다니고 우리 기관 명부에는 없다. 보호자가 우리 기관 코드로 요청했다
    id: "child_3011",
    name: "정OO",
    birthDate: "2018-11-26",
    status: "active",
    institutions: [{ institution: SCHOOL, consent: "granted" }],
  },
];

/* 기관 연결 요청 (I-09) ────────────────────────────── */

/** 보호자가 우리 기관 코드로 보낸, 기다리는 연결 요청 */
export const LINK_REQUESTS: PendingLinkRequest[] = [
  {
    child: { id: "child_3011", name: "정OO", birthDate: "2018-11-26" },
    requesters: [{ name: "정OO 어머니" }],
    requestedAt: "2026-10-29T09:20:00+09:00",
  },
];

/* 확인 필요 큐 (I-06) ──────────────────────────────── */

/**
 * 본문에서 word 가 처음 나오는 구간. AI 처럼 **코드포인트** 인덱스로 돌려준다.
 * 손으로 숫자를 적으면 본문을 고칠 때마다 어긋나서 계산으로 둔다.
 */
function spanOf(text: string, word: string): EvidenceSpan {
  const at = text.indexOf(word);
  if (at === -1) throw new Error(`mock: "${word}" 가 본문에 없습니다`);
  const start = Array.from(text.slice(0, at)).length;
  return { start, end: start + Array.from(word).length };
}

/**
 * 서버(RecordResponse)처럼 앞 60자만 잘라 목록용 미리보기를 만든다.
 * mq_01·02·05 는 아이 이름이 60자 뒤에 있어서, 미리보기만으로는 누구 기록인지 보이지 않는다.
 */
function recordText(full: string) {
  return { preview: full.length <= 60 ? full : `${full.slice(0, 60)}…`, fullContent: full };
}

const MQ_01 =
  "11:40 급식실 입장 직후 소리를 지르며 문 앞에 주저앉음. 담당 교사가 손을 잡고 30까지 함께 세자 조금씩 진정됨. 이후 자리에 앉아 식사를 마쳤고, 김OO는 평소 좋아하던 반찬을 먼저 집어 먹음.";
const MQ_02 =
  "자유놀이 중 김OO가 블록을 높이 쌓다가 무너지자 잠시 울먹였지만, 다시 처음부터 쌓기 시작함. 한참 뒤 옆자리의 이OO가 블록을 나눠 주었고, 둘이 서로 양보하며 끝까지 완성함.";
const MQ_03 = "우리 반 막둥이가 새 신발을 자랑하며 친구들에게 보여줌.";
const MQ_04 = "오후 자유놀이 중 정OO가 장난감을 두고 다툼이 있었고, 스스로 사과함.";
// 하루 전체를 적은 긴 일지 — 본문 영역이 최대 높이에서 멈추고 스크롤되는지 보는 데모 (#150)
const MQ_05 = [
  "등원 직후 표정이 굳어 있었고, 교사의 인사에도 반응하지 않은 채 가방을 내려놓지 않음. 10분쯤 지나 좋아하는 자동차 장난감을 건네자 받아 들었음. 최OO는 그 뒤로 친구들 놀이에 스스로 참여함.",
  "10:30 미술 활동. 물감 색을 고를 때 한참 망설였지만 교사가 두 가지만 골라 보여주자 파란색을 골라 끝까지 칠함. 붓을 씻는 순서를 기다리는 동안 손을 흔들며 기다리기 어려워했고, 순서 카드를 보여주니 다시 줄에 섬.",
  "11:20 바깥놀이. 모래놀이터에서 혼자 구덩이를 파다가 친구가 다가오자 삽을 하나 건네줌. 함께 물길을 만들며 웃는 모습이 여러 번 보였음. 들어갈 시간을 알리자 처음에는 싫다고 했지만 5분 전 예고를 한 번 더 하자 스스로 정리함.",
  "12:00 점심. 새로 나온 나물 반찬은 냄새를 맡아 보고 먹지 않았음. 억지로 권하지 않고 옆 친구가 먹는 모습을 보여주니 한 젓가락 맛보았음. 밥과 국은 남기지 않고 다 먹음.",
  "13:30 낮잠. 잠들기까지 20분 정도 뒤척였고 담요를 머리끝까지 덮어야 안정됨. 깨어난 뒤 잠시 멍하게 앉아 있다가 간식 소리를 듣고 일어남.",
  "15:00 간식 후 자유놀이. 블록 영역에서 지난주에 만들던 주차장을 다시 이어서 만들고 싶다고 말로 표현함. 원하는 것을 먼저 말한 것은 이번 주 들어 처음이라 기록해 둠.",
  "16:20 하원. 보호자를 보자 뛰어가 안겼고, 오늘 만든 그림을 직접 보여주며 설명함. 내일 아침 등원 때 표정이 다시 굳는지 살펴볼 예정.",
].join("\n\n");
const MQ_06 = "점심 식사 후 최OO가 블록으로 높은 탑을 쌓고 친구들에게 자랑함.";
const MQ_07 = "오후 간식 시간에 박OO가 컵을 엎었지만 스스로 휴지를 가져와 닦음.";
const MQ_08 = "바깥놀이 시간에 미끄럼틀 차례를 기다리며 친구에게 먼저 양보함.";

export const MATCHING_QUEUE: MatchingItem[] = [
  {
    id: "mq_01",
    status: "multi",
    matchedChildId: null,
    multiReason: "ambiguous_identity",
    record: {
      id: "rrf_7f2a",
      fileName: "0821_관찰일지.docx",
      type: "관찰일지",
      capturedAt: "2026-08-21T11:40:00+09:00",
      ...recordText(MQ_01),
    },
    evidence: [spanOf(MQ_01, "김OO")],
    // 후보는 명부(CHILDREN)에 있는 아이여야 한다 — 화면이 명부와 맞춰보고 없으면 막는다
    candidates: [
      { childId: "child_1023", name: "김OO", group: "나비반 · 4세반", birthDate: "2019-03-12" },
      { childId: "child_1088", name: "김OO", group: "나비반 · 4세반", birthDate: "2021-09-02" },
    ],
  },
  {
    id: "mq_02",
    status: "multi",
    matchedChildId: null,
    multiReason: "co_mention",
    record: {
      id: "rrf_7f3b",
      fileName: "0821_활동일지.docx",
      type: "활동일지",
      capturedAt: "2026-08-21T14:20:00+09:00",
      ...recordText(MQ_02),
    },
    evidence: [spanOf(MQ_02, "김OO"), spanOf(MQ_02, "이OO")],
    candidates: [
      { childId: "child_1023", name: "김OO", group: "나비반 · 4세반", birthDate: "2019-03-12" },
      { childId: "child_1041", name: "이OO", group: "나비반 · 4세반", birthDate: "2018-07-02" },
    ],
  },
  {
    id: "mq_03",
    status: "unmatched",
    matchedChildId: null,
    unmatchedReason: "no_anchor",
    record: {
      id: "rrf_8c11",
      fileName: "0821_특이사항.txt",
      type: "특이사항",
      capturedAt: "2026-08-21T15:05:00+09:00",
      ...recordText(MQ_03),
    },
    // 이름은 없지만 "막둥이" 가 누구를 가리키는지 사람이 판단할 단서다
    evidence: [spanOf(MQ_03, "막둥이")],
    candidates: [],
  },
  {
    id: "mq_04",
    status: "unmatched",
    matchedChildId: null,
    unmatchedReason: "not_in_roster",
    hintName: "정OO",
    record: {
      id: "rrf_8c24",
      fileName: "0821_정OO_관찰.docx",
      type: "관찰일지",
      capturedAt: "2026-08-21T16:30:00+09:00",
      ...recordText(MQ_04),
    },
    evidence: [spanOf(MQ_04, "정OO")],
    candidates: [],
  },
  {
    // 표지(이OO)와 본문 판정(최OO)이 어긋나 확인으로 내려온 건.
    // review 의 추천 아이는 matchedChildId 로만 온다 — candidates 는 multi 전용이다.
    id: "mq_05",
    status: "review",
    matchedChildId: "child_2044",
    hintName: "이OO",
    hintMismatch: true,
    record: {
      id: "rrf_9d02",
      fileName: "0820_이OO_활동일지.docx",
      type: "활동일지",
      capturedAt: "2026-08-20T10:10:00+09:00",
      ...recordText(MQ_05),
    },
    evidence: [spanOf(MQ_05, "최OO")],
    candidates: [],
  },
  {
    // 추천 아이가 명부에 있지만 보호자 동의 전이라 확정할 수 없는 건 (child_1077 은 pending_consent)
    id: "mq_07",
    status: "review",
    matchedChildId: "child_1077",
    record: {
      id: "rrf_9d21",
      fileName: "0820_관찰일지.docx",
      type: "관찰일지",
      capturedAt: "2026-08-20T15:40:00+09:00",
      ...recordText(MQ_07),
    },
    evidence: [spanOf(MQ_07, "박OO")],
    candidates: [],
  },
  {
    // 추천 아이가 명부에서 지워진 건 — 명부에서 찾지 못하면 직접 고르게 한다
    id: "mq_08",
    status: "review",
    matchedChildId: "child_9001",
    record: {
      id: "rrf_9d35",
      fileName: "0819_활동일지.docx",
      type: "활동일지",
      capturedAt: "2026-08-19T11:05:00+09:00",
      ...recordText(MQ_08),
    },
    evidence: [],
    candidates: [],
  },
  {
    // 일지 본문이 비어 있는 건. 서버(RecordResponse.of)는 preview 와 fullContent 를 같은 본문으로
    // 만들어서 둘이 함께 null 로 온다. 화면은 본문을 못 불러왔다고 안내한다 (#150).
    id: "mq_09",
    status: "unmatched",
    matchedChildId: null,
    unmatchedReason: "no_anchor",
    record: {
      id: "rrf_9d48",
      fileName: "0819_관찰일지.hwp",
      type: "관찰일지",
      capturedAt: "2026-08-19T09:50:00+09:00",
      preview: null,
      fullContent: null,
    },
    evidence: [],
    candidates: [],
  },
  {
    // AI 호출이 실패한 건. 판정이 없으니 후보도 근거도 비어 온다.
    id: "mq_06",
    status: "failed",
    matchedChildId: null,
    record: {
      id: "rrf_9d17",
      fileName: "0820_활동일지.docx",
      type: "활동일지",
      capturedAt: "2026-08-20T13:50:00+09:00",
      ...recordText(MQ_06),
    },
    evidence: [],
    candidates: [],
  },
];

/* 파일 단위 처리 현황 ─────────────────────────────── */

const entries = (
  prefix: string,
  groups: [count: number, stageIndex: number, state: EntryProgress["state"]][],
): EntryProgress[] => {
  let n = 0;
  return groups.flatMap(([count, stageIndex, state]) =>
    Array.from({ length: count }, () => ({ id: `${prefix}_${++n}`, stageIndex, state })),
  );
};

/**
 * 업로드한 파일별로, 거기서 나온 기록이 지금 어느 단계에 있는지.
 * 단계 인덱스는 lib/pipeline.ts 의 PIPELINE_STAGES 기준 (0 등록 · 1 매칭 · 2 검증 · 3 요약 · 4 1차 검토).
 */
export const FILE_PROGRESS: FileProgress[] = [
  {
    rawRecordId: "rr_0915",
    fileName: "관찰일지_9월3주.docx",
    uploadedAt: "2026-09-19T17:10:00+09:00",
    entries: entries("rr_0915", [
      [3, 5, "done"],
      [8, 4, "waiting"],
      [1, 3, "failed"],
    ]),
  },
  {
    rawRecordId: "rr_0821a",
    fileName: "0821_관찰일지.docx",
    uploadedAt: "2026-08-21T18:02:00+09:00",
    entries: entries("rr_0821a", [
      [2, 1, "waiting"],
      [2, 2, "running"],
      [1, 4, "waiting"],
      // 매칭 확인에서 "우리 기관 아이 아님"으로 뺀 기록 (BE EXCLUDED)
      [1, 1, "done"],
    ]),
  },
  {
    rawRecordId: "rr_0821c",
    fileName: "0821_특이사항.txt",
    uploadedAt: "2026-08-21T18:05:00+09:00",
    // 검증에서 BLOCK — 원본을 고쳐야 하므로 사람 대기다
    entries: entries("rr_0821c", [[1, 2, "waiting"]]),
  },
];

/* 재입력 요청 큐 (I-07) ────────────────────────────── */

export const BLOCKED_QUEUE: BlockedItem[] = [
  {
    id: "bq_01",
    childName: "김OO",
    violationReason: "다른 아이 이름이 함께 포함되어 있습니다",
    record: {
      id: "rrf_a301",
      fileName: "0819_관찰일지.txt",
      type: "관찰일지",
      capturedAt: "2026-08-19",
      preview: "김OO와 이OO가 함께 블록놀이를 했고, 이OO가 먼저 자리를 떠났다.",
      fullContent: "김OO와 이OO가 함께 블록놀이를 했고, 이OO가 먼저 자리를 떠났다.",
    },
  },
  {
    id: "bq_02",
    childName: null,
    violationReason: "진단으로 읽히는 확정적 표현이 포함되어 있습니다",
    record: {
      id: "rrf_a402",
      fileName: "0818_특이사항.txt",
      type: null,
      capturedAt: "2026-08-18",
      // 막힌 문장이 60자 뒤에 있는 경우 — 미리보기로는 무엇을 고칠지 보이지 않는다
      preview:
        "오전 간식 시간에는 평소처럼 자리에 앉아 우유를 마셨고, 친구가 건넨 과자도 받아 먹었다. 바깥놀이…",
      fullContent:
        "오전 간식 시간에는 평소처럼 자리에 앉아 우유를 마셨고, 친구가 건넨 과자도 받아 먹었다. 바깥놀이 시간에는 미끄럼틀 앞에서 차례를 기다렸다. 요즘 상태를 보면 자폐 성향이 심해지고 있는 것으로 보인다.",
    },
  },
];

/* Gate 1 검토 대기 (I-08) ──────────────────────────── */

/** 요약 본문은 문장(claims)을 이은 것이다 — mock 도 그렇게 만든다 */
function gate1Mock(
  item: Omit<SummaryItem, "content" | "claims"> & { sentences: string[] },
): SummaryItem {
  const { sentences, ...rest } = item;
  return {
    ...rest,
    content: sentences.join(" "),
    claims: sentences.map((text, index) => ({ index, text })),
  };
}

export const GATE1_QUEUE: SummaryItem[] = [
  // 다른 아이 이름 경고 + 요약에 안 들어간 일지 + 검증 REVIEW
  gate1Mock({
    id: "sum_9012",
    childId: "child_1023",
    childName: "김OO",
    entryDate: "2026-08-21",
    revision: 2,
    gate1Status: "pending",
    version: "mock_v_9012",
    processing: false,
    sentences: [
      "블록 쌓기 활동에서 블록을 세 번 쌓았다.",
      "자유놀이 시간에 박OO와 장난감을 두고 다퉜다.",
      "점심을 남기지 않고 다 먹었다.",
    ],
    noContent: false,
    needsReview: true,
    reviewReasons: ["다른아동이름"],
    uncoveredEntries: [{ journalEntryId: "je_5103", content: "내일 하원 시간이 오후 4시로 바뀝니다." }],
    reviewFlags: [{ journalEntryId: "je_5101", issueTypes: ["추측성표현"] }],
  }),
  // 같은 날 새 기록을 반영하는 중 — 고치기·승인이 잠긴다
  gate1Mock({
    id: "sum_9013",
    childId: "child_1041",
    childName: "이OO",
    entryDate: "2026-08-20",
    revision: 1,
    gate1Status: "pending",
    version: "mock_v_9013",
    processing: true,
    sentences: ["오전 감각 놀이 시간에 모래를 10분 동안 만졌다.", "간식 시간에 스스로 컵을 들고 마셨다."],
    noContent: false,
    needsReview: false,
    reviewReasons: [],
    uncoveredEntries: [],
    reviewFlags: [],
  }),
  // 문장을 나누지 못한 요약 — 그대로 승인하거나 제거만 된다
  {
    id: "sum_9015",
    childId: "child_1088",
    childName: "김OO",
    entryDate: "2026-08-20",
    revision: 1,
    gate1Status: "pending",
    version: "mock_v_9015",
    processing: false,
    content: "오후 자유놀이 종료 안내 후 스스로 정리하고 다음 활동으로 이동함.",
    claims: [],
    noContent: false,
    needsReview: false,
    reviewReasons: [],
    uncoveredEntries: [],
    reviewFlags: [],
  },
  // 요약할 내용 없음 (#244) — 본문·문장이 비고, 묶인 기록 원본이 uncoveredEntries 로 온다
  {
    id: "sum_9016",
    childId: "child_2044",
    childName: "최OO",
    entryDate: "2026-08-21",
    revision: 1,
    gate1Status: "pending",
    version: "mock_v_9016",
    processing: false,
    content: "",
    claims: [],
    noContent: true,
    needsReview: false,
    reviewReasons: [],
    uncoveredEntries: [
      { journalEntryId: "je_5201", content: "특이사항 없음." },
      { journalEntryId: "je_5202", content: "오후 일정 평소와 같음." },
    ],
    reviewFlags: [],
  },
  gate1Mock({
    id: "sum_9014",
    childId: "child_1077",
    childName: "박OO",
    entryDate: "2026-08-19",
    revision: 1,
    gate1Status: "hold",
    version: "mock_v_9014",
    processing: false,
    sentences: ["언어 치료 시간에 두 단어 문장을 세 번 말했다.", "하원 전 정리 시간에 울음을 보였다."],
    noContent: false,
    needsReview: false,
    reviewReasons: [],
    uncoveredEntries: [],
    reviewFlags: [],
  }),
];

/* 오늘의 업무 묶음 카드 (O-34) ─────────────────────── */

/** 라벨 다섯 가지와 버튼 세 갈래(강조 · 약하게 · 꺼짐)가 모두 보이게 골랐다. 오래된 날짜부터 */
export const SUMMARY_GROUP_BOARD: SummaryGroupBoard = {
  matching: { inProgress: 3, needsReview: 2 },
  groups: [
    {
      childId: "child_2044",
      childName: "최OO",
      birthdate: "2021-05-30",
      entryDate: "2026-08-18",
      status: "admin_check",
      counts: { validating: 0, needsFix: 0, ready: 0, summarizing: 0, adminCheck: 1 },
      canSummarize: false,
      summaryRequested: false,
      waitingCount: 0,
      previousSummaryInReview: false,
    },
    {
      childId: "child_1077",
      childName: "박OO",
      birthdate: "2020-01-25",
      entryDate: "2026-08-19",
      status: "needs_fix",
      counts: { validating: 0, needsFix: 1, ready: 0, summarizing: 0, adminCheck: 0 },
      canSummarize: false,
      summaryRequested: false,
      waitingCount: 0,
      previousSummaryInReview: false,
    },
    {
      childId: "child_1023",
      childName: "김OO",
      birthdate: "2019-03-12",
      entryDate: "2026-08-20",
      status: "needs_fix",
      counts: { validating: 1, needsFix: 1, ready: 2, summarizing: 0, adminCheck: 0 },
      canSummarize: true,
      summaryRequested: false,
      waitingCount: 0,
      previousSummaryInReview: false,
    },
    {
      childId: "child_1041",
      childName: "이OO",
      birthdate: "2018-07-02",
      entryDate: "2026-08-20",
      status: "ready",
      counts: { validating: 0, needsFix: 0, ready: 3, summarizing: 0, adminCheck: 0 },
      canSummarize: true,
      summaryRequested: false,
      waitingCount: 0,
      previousSummaryInReview: false,
    },
    // 같은 이름 — 생년월일로 구분한다
    {
      childId: "child_1088",
      childName: "김OO",
      birthdate: "2021-09-02",
      entryDate: "2026-08-21",
      status: "ready",
      counts: { validating: 0, needsFix: 0, ready: 2, summarizing: 0, adminCheck: 0 },
      canSummarize: true,
      summaryRequested: false,
      waitingCount: 0,
      previousSummaryInReview: true,
    },
    {
      childId: "child_1041",
      childName: "이OO",
      birthdate: "2018-07-02",
      entryDate: "2026-08-21",
      status: "summarizing",
      counts: { validating: 2, needsFix: 0, ready: 1, summarizing: 0, adminCheck: 0 },
      canSummarize: false,
      summaryRequested: true,
      waitingCount: 2,
      previousSummaryInReview: false,
    },
    {
      childId: "child_1023",
      childName: "김OO",
      birthdate: "2019-03-12",
      entryDate: "2026-08-21",
      status: "validating",
      counts: { validating: 2, needsFix: 0, ready: 0, summarizing: 0, adminCheck: 0 },
      canSummarize: false,
      summaryRequested: false,
      waitingCount: 0,
      previousSummaryInReview: false,
    },
  ],
};

let gate1RunSeq = 0;

/** mock 전용: [요약]을 마친 묶음이 1차 검토에 올리는 요약 한 건 */
export function gate1FromRun(group: SummaryGroup): SummaryItem {
  gate1RunSeq += 1;
  return gate1Mock({
    id: `sum_run_${gate1RunSeq}`,
    childId: group.childId,
    childName: group.childName,
    entryDate: group.entryDate,
    revision: group.previousSummaryInReview ? 2 : 1,
    gate1Status: "pending",
    version: `mock_v_run_${gate1RunSeq}`,
    processing: false,
    sentences: ["오전 활동 시간에 끝까지 자리에 앉아 참여했다.", "점심을 남기지 않고 먹었다."],
    noContent: false,
    needsReview: false,
    reviewReasons: [],
    uncoveredEntries: [],
    reviewFlags: [],
  });
}

/* Child Context 타임라인 (I-09) ───────────────────── */

export const TIMELINE: Record<string, TimelineEntry[]> = {
  child_1023: [
    {
      date: "2026-08-21",
      entry: {
        recordType: "활동일지",
        validation: "PASS",
        content: "11:40 급식실 입장 직후 소리를 지름. 손을 잡고 30까지 세자 진정됨.",
        sourceCount: 1,
        version: 1,
        edited: false,
      },
    },
    { date: "2026-08-20", entry: null },
    {
      date: "2026-08-19",
      entry: {
        recordType: "특이사항",
        validation: "PASS",
        content: "오후 자유놀이 종료 안내 후 스스로 정리하고 다음 활동으로 이동함.",
        sourceCount: 1,
        version: 2,
        edited: true,
      },
    },
    { date: "2026-08-18", entry: null },
    { date: "2026-08-17", entry: null },
    {
      date: "2026-08-16",
      entry: {
        recordType: "관찰일지",
        validation: "PASS",
        content: "식사 시간에 새로운 반찬을 두 번 거부한 뒤 세 번째 권유에 소량 섭취함.",
        sourceCount: 2,
        version: 1,
        edited: false,
      },
    },
  ],
  // 방금 등록한 아이 — 승인된 요약이 아직 0건 (Cold Start 첫 화면)
  child_1077: [],
  child_1041: [
    {
      date: "2026-08-18",
      entry: {
        recordType: "관찰일지",
        validation: "PASS",
        content: "이동 전 5분 전에 미리 안내했을 때 전환이 수월했음.",
        sourceCount: 1,
        version: 1,
        edited: false,
      },
    },
  ],
};

/* Insight (I-10, I-11) ─────────────────────────────── */

export const INSIGHTS: Insight[] = [
  {
    id: "ins_5501",
    childId: "child_1023",
    childName: "김OO",
    period: "최근 3주",
    content: "소음 상황에서 반응하는 패턴이 3회 반복됨.",
    needsReview: true,
    primarySource: CENTER,
    evidence: [
      {
        childContextId: "cc_3301",
        date: "2026-08-21",
        label: "급식실 기록",
        institution: CENTER,
      },
      {
        childContextId: "cc_3288",
        date: "2026-08-14",
        label: "놀이치료실 기록",
        institution: CENTER,
      },
      {
        childContextId: "cc_3270",
        date: "2026-08-07",
        label: "등원 기록",
        institution: CENTER,
      },
    ],
    targets: [
      { institution: SCHOOL, consent: "granted" },
      { institution: ASSISTANT, consent: "granted" },
      { institution: SPEECH, consent: "not_granted" },
    ],
    gate2Status: "pending",
  },
  {
    id: "ins_5502",
    childId: "child_1041",
    childName: "이OO",
    period: "최근 4주",
    content: "이동·전환 상황에서 미리 안내한 날에는 불안 반응이 줄었음.",
    needsReview: true,
    primarySource: CENTER,
    evidence: [
      {
        childContextId: "cc_3199",
        date: "2026-08-18",
        label: "나들이 기록",
        institution: CENTER,
      },
      {
        childContextId: "cc_3150",
        date: "2026-08-11",
        label: "등원 기록",
        institution: CENTER,
      },
    ],
    targets: [{ institution: SCHOOL, consent: "revoked" }],
    gate2Status: "hold",
  },
  {
    id: "ins_5503",
    childId: "child_1023",
    childName: "김OO",
    period: "최근 6주",
    content: "식사 거부 횟수가 4주간 감소 추세를 보임.",
    needsReview: true,
    primarySource: CENTER,
    evidence: [
      {
        childContextId: "cc_3120",
        date: "2026-08-16",
        label: "급식실 기록",
        institution: CENTER,
      },
    ],
    targets: [{ institution: SCHOOL, consent: "granted" }],
    gate2Status: "approved",
  },
];

/* 수신함 (I-12) ────────────────────────────────────── */

// 단건(O-51)이 본문까지 주므로 mock 은 본문을 함께 들고 있다. 목록(getInbox)은 본문을 떼고 준다.
export const INBOX: InboxDetail[] = [
  {
    id: "in_01",
    childId: "child_1088",
    childName: "김OO",
    externalId: "C-1088",
    from: SCHOOL,
    period: "2026년 10월 4주차",
    receivedAt: "2026-10-29T15:20:00",
    readAt: null,
    content:
      "급식 시간 소음에 반응할 때, 손을 잡고 숫자를 세면 진정에 도움이 됩니다.",
  },
  {
    id: "in_02",
    childId: "child_1088",
    childName: "김OO",
    externalId: "C-1088",
    from: ASSISTANT,
    period: "2026년 10월 4주차",
    receivedAt: "2026-10-29T10:05:00",
    readAt: null,
    content: "이동 전 5분 전에 미리 안내하면 전환 시 불안이 줄어듭니다.",
  },
  {
    id: "in_03",
    childId: "child_2044",
    childName: "최OO",
    externalId: "C-2044",
    from: SCHOOL,
    period: "2026년 10월 3주차",
    receivedAt: "2026-10-22T09:40:00",
    readAt: "2026-10-22T13:12:00",
    content:
      "새로운 활동 시작 전 그림 카드로 순서를 보여주면 참여도가 높아집니다.",
  },
];

/* 상담 도우미 (I-13) ───────────────────────────────── */

export const CHAT_EXAMPLES: ChatTurn[] = [
  {
    question: "최근에 식사 거부가 줄었나요?",
    answer:
      "승인된 기록 기준으로, 최근 4주간 식사 거부 횟수가 줄었습니다. 손을 잡고 함께 세는 방식이 기록된 날에는 진정까지 걸린 시간이 짧았습니다.",
    sources: [
      { date: "2026-08-21", label: "급식실 기록" },
      { date: "2026-08-14", label: "급식실 기록" },
    ],
  },
  {
    question: "밤에 잠을 잘 못 자나요?",
    // 근거가 없으면 답변을 생성하지 않는다
    answer: null,
    sources: [],
  },
];

/* 활동 이력 (I-14) ─────────────────────────────────── */

export const ACTIVITY: ActivityLog[] = [
  {
    id: "al_01",
    at: "2026-08-21 14:02",
    actor: "박지현 선생님",
    action: "Gate 1 승인",
    target: "김OO · 08.21 활동일지 요약",
  },
  {
    id: "al_02",
    at: "2026-08-21 13:48",
    actor: "박지현 선생님",
    action: "확인 필요 큐 아동 지정",
    target: "0821_관찰일지.docx → 김OO",
  },
  {
    id: "al_03",
    at: "2026-08-20 17:30",
    actor: "박지현 선생님",
    action: "Gate 2 발송 승인",
    target: "김OO · 최근 6주 Insight → OO초등학교",
  },
  {
    id: "al_04",
    at: "2026-08-20 09:15",
    actor: "OO초등학교",
    action: "수신함 조회",
    target: "김OO 관련 지원 방법",
  },
];

/* 학부모 화면 ──────────────────────────────────────── */

/**
 * 한 보호자 계정에 연결된 아이들. CHILDREN 은 시스템 전체 아동(다른 기관·다른
 * 가정 포함) 목록이고, 이 배열은 그중 "지금 로그인한 보호자"의 아이만 골라낸
 * 뷰다 — 같은 Child 객체를 참조하므로 mutate 계열 API(mock.CHILDREN.find(...))가
 * 그대로 통한다.
 */
export const PARENT_CHILDREN: Child[] = CHILDREN.filter((c) =>
  ["child_1023", "child_2044"].includes(c.id),
);

export const PARENT_CHILD = PARENT_CHILDREN[0];

export const PARENT_ACTIVITY: Record<string, ParentActivity[]> = {
  child_1023: [
    { id: "pa_01", kind: "journal", at: "오늘 09:12", text: "OO초등학교가 일일 요약을 보냈어요", journalId: "jrn_01" },
    {
      id: "pa_02",
      kind: "insight",
      at: "어제 17:40",
      text: `${SCHOOL.name}에서 10월 4주차 리포트 2편이 도착했어요`,
      insightId: null,
    },
    {
      id: "pa_03",
      kind: "insight",
      at: "3일 전",
      text: `${SCHOOL.name}에서 10월 3주차 리포트가 도착했어요`,
      insightId: "gi_03",
    },
  ],
  child_2044: [
    { id: "pa_04", kind: "insight", at: "3일 전", text: "10월 4주차 리포트가 도착했어요", insightId: "gi_04" },
  ],
};

/**
 * 보호자가 받은 인사이트 (G-35 · G-36 mock). 승인 시각 최근 순 — 서버와 같은 순서로 둔다.
 * 한 기간에 두 편(하나는 읽음), 함께 보는 기관이 있는 글과 없는 글, 승인 기관을 모르는 글(from: null)을 함께 둔다.
 */
export const GUARDIAN_INSIGHTS: GuardianInsight[] = [
  {
    id: "gi_01",
    childId: "child_1023",
    childName: "김OO",
    period: "2026년 10월 4주차",
    content: "활동이 바뀌기 5분 전에 그림 카드로 예고하면, 학교와 센터 모두에서 울음 없이 다음 활동으로 옮겨 갔습니다.",
    from: SCHOOL,
    sharedWith: [CENTER],
    approvedAt: "2026-10-29T17:40:00",
    readAt: null,
  },
  {
    id: "gi_02",
    childId: "child_1023",
    childName: "김OO",
    period: "2026년 10월 4주차",
    content: "점심 직후에는 소리에 예민해져, 조용한 자리를 먼저 마련하면 오후 활동 참여가 늘었습니다.",
    from: SCHOOL,
    sharedWith: [],
    approvedAt: "2026-10-29T17:20:00",
    readAt: "2026-10-29T20:05:00",
  },
  {
    id: "gi_03",
    childId: "child_1023",
    childName: "김OO",
    period: "2026년 10월 3주차",
    content: "학교에서는 차례를 기다렸지만 센터에서는 끼어드는 일이 잦았습니다. 센터에서도 순서 카드를 쓰면 도움이 될 수 있습니다.",
    from: SCHOOL,
    sharedWith: [CENTER],
    approvedAt: "2026-10-27T10:00:00",
    readAt: "2026-10-27T21:00:00",
  },
  {
    id: "gi_04",
    childId: "child_2044",
    childName: "최OO",
    period: "2026년 10월 4주차",
    content: "첫 주에는 미술 활동을 거부했지만, 넷째 주에는 스스로 붓을 들었습니다.",
    from: null,
    sharedWith: [],
    approvedAt: "2026-10-28T09:00:00",
    readAt: null,
  },
];

/**
 * 아이별 초대 코드 (O-15~O-17 mock). 만료 여부는 조회할 때 expiresAt 으로 거른다.
 * 10분짜리라 미리 넣어 두면 화면을 열 때쯤 이미 만료돼 있어 비워 둔다.
 */
export const INVITATIONS: Record<string, LiveInvitation[]> = {};

/** 아이별 연결된 보호자 (O-18, O-19 mock) */
export const CHILD_GUARDIANS: Record<string, ChildGuardian[]> = {
  child_1023: [
    { userId: "41", name: "김보호", linkedAt: "2026-09-21T10:03:00+09:00", linkedByThisOrganization: true },
    { userId: "52", name: null, linkedAt: "2026-09-25T18:20:00+09:00", linkedByThisOrganization: false },
  ],
  child_1041: [
    { userId: "63", name: "이보호", linkedAt: "2026-09-02T09:12:00+09:00", linkedByThisOrganization: true },
  ],
};

/**
 * 보호자가 초대 코드를 수락하면 연결되는 아이 (G-04 mock). 아직 이 보호자의 아이가 아니고
 * 우리 기관 동의가 남은 아이라, 수락 뒤 동의 미리보기(G-02 mock)까지 이어서 볼 수 있다.
 */
export const INVITED_CHILD_ID = "child_1077";

/** 학부모를 초대한 기관 (P-02 확인·동의 화면) */
export const INVITING_INSTITUTION = SCHOOL;

/** 오늘의 일지 · 타임라인 */
export const JOURNAL: JournalEntry[] = [
  {
    id: "jrn_01",
    childId: PARENT_CHILD.id,
    institution: SCHOOL,
    date: "2026-08-21",
    isNew: true,
    content:
      "오늘 원영이는 블록 영역에서 40분 정도 놀았습니다. 친구와 함께 탑을 쌓다가 두 번 무너졌지만 다시 쌓아 완성했습니다. 점심은 모두 먹었고, 낮잠 없이 오후 활동까지 잘 참여했습니다.",
  },
  {
    id: "jrn_02",
    childId: PARENT_CHILD.id,
    institution: CENTER,
    date: "2026-08-21",
    isNew: true,
    content:
      "체육 활동으로 공 주고받기를 했습니다. 처음에는 세 번 정도였는데 마지막에는 아홉 번까지 이어갔습니다. 활동이 끝난 뒤 스스로 정리했습니다.",
  },
  {
    id: "jrn_03",
    childId: PARENT_CHILD.id,
    institution: ASSISTANT,
    date: "2026-08-20",
    isNew: false,
    content:
      "18시 40분에 하원했고 집까지 20분 걸었습니다. 오늘 만든 그림을 보여주며 이야기를 계속 했습니다. 간식은 모두 먹었습니다.",
  },
  {
    id: "jrn_04",
    childId: PARENT_CHILD.id,
    institution: SCHOOL,
    date: "2026-08-14",
    isNew: false,
    content: "블록 놀이 중 친구와 부딪히는 일이 두 번 있었고, 함께 정리했습니다.",
  },
  {
    id: "jrn_05",
    childId: PARENT_CHILD.id,
    institution: CENTER,
    date: "2026-08-14",
    isNew: false,
    content: "놀이 종료 안내를 듣고 스스로 정리했습니다.",
  },
  {
    id: "jrn_06",
    childId: PARENT_CHILD.id,
    institution: SCHOOL,
    date: "2026-08-07",
    isNew: false,
    content: "오후 자유놀이 시간에 비슷한 상황이 있었습니다.",
  },
];

/** 설정 › 기관 요청사항 — 아이별. 아직 요청이 없는 아이는 빈 배열. */
export const INSTITUTION_REQUESTS: Record<string, InstitutionRequestItem[]> = {
  child_1023: [
    {
      id: "req_01",
      institution: SCHOOL,
      status: "confirmed",
      items: ["다음 주 현장학습 동의서를 확인해주세요.", "실내화를 새로 준비해주세요."],
    },
    {
      id: "req_02",
      institution: CENTER,
      status: "needs_check",
      items: ["간식 알레르기 정보를 확인해주세요."],
    },
    {
      id: "req_03",
      institution: ASSISTANT,
      status: "needs_check",
      items: ["하원 동행 시간을 10분 늦춰도 될까요."],
    },
  ],
  child_2044: [],
};
