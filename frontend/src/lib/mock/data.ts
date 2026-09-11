import type {
  ActivityLog,
  BlockedItem,
  Child,
  ChatTurn,
  InboxItem,
  Insight,
  Institution,
  MatchingItem,
  ParentActivity,
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

/** 로그인한 교사가 소속된 기관 */
export const MY_INSTITUTION = CENTER;
export const ME = { name: "박지현", role: "선생님" };

/* 아동 ─────────────────────────────────────────────── */

export const CHILDREN: Child[] = [
  {
    id: "child_1023",
    name: "김OO",
    birthDate: "2019-03-12",
    status: "active",
    institutions: [
      { institution: CENTER, consent: "granted" },
      { institution: SCHOOL, consent: "granted" },
      { institution: ASSISTANT, consent: "granted" },
      { institution: SPEECH, consent: "not_granted" },
    ],
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
];

/* 확인 필요 큐 (I-06) ──────────────────────────────── */

export const MATCHING_QUEUE: MatchingItem[] = [
  {
    id: "mq_01",
    status: "multi",
    confidence: 0.42,
    record: {
      id: "rrf_7f2a",
      fileName: "0821_관찰일지.docx",
      type: "관찰일지",
      capturedAt: "2026-08-21T11:40:00+09:00",
      preview: "11:40 급식실 입장 직후 소리를 지름. 손을 잡고 30까지 세자 진정됨.",
    },
    candidates: [
      { childId: "child_1023", name: "김OO", group: "나비반 · 4세반" },
      { childId: "child_1041", name: "이OO", group: "나비반 · 4세반" },
    ],
  },
  {
    id: "mq_02",
    status: "unmatched",
    confidence: null,
    record: {
      id: "rrf_8c11",
      fileName: "0821_특이사항.txt",
      type: "특이사항",
      capturedAt: "2026-08-21T15:05:00+09:00",
      preview: "오후 자유놀이 중 다른 아이와 장난감을 두고 다툼이 있었음.",
    },
    candidates: [],
  },
  {
    id: "mq_03",
    status: "low",
    confidence: 0.58,
    record: {
      id: "rrf_9d02",
      fileName: "0820_활동일지.docx",
      type: "활동일지",
      capturedAt: "2026-08-20T10:10:00+09:00",
      preview: "등원 직후 표정이 굳어 있었고, 인사에 반응하지 않음.",
    },
    candidates: [{ childId: "child_1077", name: "박OO", group: "민들레반 · 6세반" }],
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
      fileName: "0819_관찰일지.docx",
      type: "관찰일지",
      capturedAt: "2026-08-19T14:20:00+09:00",
      preview: "김OO와 이OO가 함께 블록놀이를 했고, 이OO가 먼저 자리를 떠났다.",
    },
  },
  {
    id: "bq_02",
    childName: null,
    violationReason: "진단으로 읽히는 확정적 표현이 포함되어 있습니다",
    record: {
      id: "rrf_a402",
      fileName: "0818_특이사항.txt",
      type: "특이사항",
      capturedAt: "2026-08-18T16:00:00+09:00",
      preview: "요즘 상태를 보면 자폐 성향이 심해지고 있는 것으로 보인다.",
    },
  },
];

/* Gate 1 검토 대기 (I-08) ──────────────────────────── */

export const GATE1_QUEUE: SummaryItem[] = [
  {
    id: "sum_9012",
    childId: "child_1023",
    childName: "김OO",
    institutionName: CENTER.name,
    date: "2026-08-21",
    recordType: "활동일지",
    validation: "REVIEW",
    content: "11:40 급식실 입장 직후 소리를 지름. 손을 잡고 30까지 세자 진정됨.",
    flaggedSpan: "소리를 지름",
    flagReason: "진단처럼 읽히는 표현이 포함되어 확인이 필요합니다",
    gate1Status: "pending",
    sourceCount: 1,
  },
  {
    id: "sum_9013",
    childId: "child_1023",
    childName: "김OO",
    institutionName: CENTER.name,
    date: "2026-08-20",
    recordType: "관찰일지",
    validation: "PASS",
    content:
      "오후 자유놀이 종료 안내 후 스스로 정리하고 다음 활동으로 이동함.",
    gate1Status: "pending",
    sourceCount: 1,
  },
  {
    id: "sum_9014",
    childId: "child_1041",
    childName: "이OO",
    institutionName: CENTER.name,
    date: "2026-08-19",
    recordType: "활동일지",
    validation: "REVIEW",
    content: "식사 시간에 새로운 반찬을 두 번 거부한 뒤 세 번째 권유에 소량 섭취함.",
    flaggedSpan: "거부",
    flagReason: "확정적으로 읽힐 수 있는 표현입니다",
    gate1Status: "rejected",
    rejectReason: "이름이 잘못 매칭된 것 같습니다. 다시 확인해주세요.",
    sourceCount: 2,
  },
];

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
    gate2Status: "held",
  },
  {
    id: "ins_5503",
    childId: "child_1023",
    childName: "김OO",
    period: "최근 6주",
    content: "식사 거부 횟수가 4주간 감소 추세를 보임.",
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
    gate2Status: "sent",
  },
];

/* 수신함 (I-12) ────────────────────────────────────── */

export const INBOX: InboxItem[] = [
  {
    id: "in_01",
    from: SCHOOL,
    childName: "김OO",
    receivedAt: "방금 전",
    content:
      "급식 시간 소음에 반응할 때, 손을 잡고 숫자를 세면 진정에 도움이 됩니다.",
    read: false,
  },
  {
    id: "in_02",
    from: ASSISTANT,
    childName: "김OO",
    receivedAt: "2시간 전",
    content: "이동 전 5분 전에 미리 안내하면 전환 시 불안이 줄어듭니다.",
    read: false,
  },
  {
    id: "in_03",
    from: SCHOOL,
    childName: "이OO",
    receivedAt: "어제",
    content:
      "새로운 활동 시작 전 그림 카드로 순서를 보여주면 참여도가 높아집니다.",
    read: true,
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

export const PARENT_CHILD = CHILDREN[0];

export const PARENT_ACTIVITY: ParentActivity[] = [
  { id: "pa_01", at: "오늘 09:12", text: "OO초등학교가 일일 요약을 보냈어요" },
  { id: "pa_02", at: "어제 17:40", text: "OO주간보호센터가 주간 인사이트를 보냈어요" },
  { id: "pa_03", at: "3일 전", text: "일일 요약 공유 범위에 동의했어요" },
];

/** 학부모를 초대한 기관 (P-02 확인·동의 화면) */
export const INVITING_INSTITUTION = SCHOOL;
