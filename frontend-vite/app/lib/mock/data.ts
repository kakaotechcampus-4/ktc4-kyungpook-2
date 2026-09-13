import type {
  ActivityLog,
  BlockedItem,
  CareReport,
  Child,
  ChatTurn,
  InboxItem,
  Insight,
  Institution,
  InstitutionRequestItem,
  InstitutionType,
  JournalEntry,
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
      welfareCard: false,
      allergies: ["갑각류(새우)"],
      medications: [{ name: "알레르기 약", time: "점심 식후" }],
      weeklySchedule: [
        { day: "월요일", note: "센터 하원 동행" },
        { day: "수요일", note: "센터 하원 동행" },
        { day: "화·목요일", note: "17시 언어치료 수업" },
      ],
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
    // 다자녀 보호자 데모용 — child_1023 과 같은 보호자의 둘째 아이
    id: "child_2044",
    name: "최OO",
    birthDate: "2021-05-30",
    school: "OO어린이집",
    status: "active",
    institutions: [{ institution: CENTER, consent: "granted" }],
    care: { welfareCard: false, allergies: [], medications: [], weeklySchedule: [] },
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
    { id: "pa_01", at: "오늘 09:12", text: "OO초등학교가 일일 요약을 보냈어요", journalId: "jrn_01" },
    { id: "pa_02", at: "어제 17:40", text: "OO주간보호센터가 주간 인사이트를 보냈어요" },
    { id: "pa_03", at: "3일 전", text: "일일 요약 공유 범위에 동의했어요" },
  ],
  child_2044: [{ id: "pa_04", at: "3일 전", text: "OO주간보호센터 공유에 동의했어요" }],
};

/**
 * 알림에서 "거절"한 기관 요청 — "childId:institutionId" 형태로 저장한다.
 * consent 는 여전히 not_granted 로 남지만(다시 권한을 줄 수도 있으니), 거절한
 * 요청은 목록/배지에서 다시 "안읽음"으로 뜨지 않도록 이 목록으로 따로 걸러낸다.
 */
export const DECLINED_INSTITUTION_REQUESTS = new Set<string>();

/** 학부모를 초대한 기관 (P-02 확인·동의 화면) */
export const INVITING_INSTITUTION = SCHOOL;

/** 타임라인 상단 오늘 요약 카드 — 아이별. 없으면 화면에서 숨긴다. */
export const PARENT_TODAY_SUMMARY: Record<string, string> = {
  child_1023: "오늘 블록 놀이를 가장 오래 했고, 점심을 모두 먹었습니다. 하원은 4시 40분이었습니다.",
};

/** 오늘의 일지 · 타임라인 */
export const JOURNAL: JournalEntry[] = [
  {
    id: "jrn_01",
    childId: PARENT_CHILD.id,
    institution: SCHOOL,
    date: "2026-08-21",
    time: "15:20",
    isNew: true,
    tag: "활동일지",
    summary: "오늘 블록 놀이를 가장 오래 했습니다. 친구와 함께 높은 탑을 만들었습니다.",
    detail:
      "오늘 원영이는 블록 영역에서 40분 정도 놀았습니다. 친구와 함께 탑을 쌓다가 두 번 무너졌지만 다시 쌓아 완성했습니다. 점심은 모두 먹었고, 낮잠 없이 오후 활동까지 잘 참여했습니다.",
    institutionNote: "집에서도 \"다시 해보자\"는 말을 자주 해주시면 좋겠습니다.",
    photoCount: 3,
  },
  {
    id: "jrn_02",
    childId: PARENT_CHILD.id,
    institution: CENTER,
    date: "2026-08-21",
    time: "17:00",
    isNew: true,
    tag: "수업요약",
    summary: "오늘 수업에서 공 주고받기를 했습니다. 처음보다 오래 이어갔습니다.",
    detail:
      "체육 활동으로 공 주고받기를 했습니다. 처음에는 세 번 정도였는데 마지막에는 아홉 번까지 이어갔습니다. 활동이 끝난 뒤 스스로 정리했습니다.",
    institutionNote: "주말에 짧게라도 함께 공놀이를 해보시면 좋습니다.",
  },
  {
    id: "jrn_03",
    childId: PARENT_CHILD.id,
    institution: ASSISTANT,
    date: "2026-08-20",
    time: "18:00",
    isNew: false,
    tag: "하원기록",
    summary: "하원 길에 오늘 만든 그림 이야기를 계속 했습니다.",
    detail:
      "18시 40분에 하원했고 집까지 20분 걸었습니다. 오늘 만든 그림을 보여주며 이야기를 계속 했습니다. 간식은 모두 먹었습니다.",
    institutionNote: "오늘은 기분이 좋아 보였습니다.",
  },
  {
    id: "jrn_04",
    childId: PARENT_CHILD.id,
    institution: SCHOOL,
    date: "2026-08-14",
    time: "16:10",
    isNew: false,
    tag: "활동일지",
    summary: "블록 놀이 중 친구와 부딪히는 일이 두 번 있었고, 함께 정리했습니다.",
    detail: "블록 놀이 중 친구와 부딪히는 일이 두 번 있었고, 함께 정리했습니다.",
  },
  {
    id: "jrn_05",
    childId: PARENT_CHILD.id,
    institution: CENTER,
    date: "2026-08-14",
    time: "15:40",
    isNew: false,
    tag: "수업요약",
    summary: "놀이 종료 안내를 듣고 스스로 정리했습니다.",
    detail: "놀이 종료 안내를 듣고 스스로 정리했습니다.",
  },
  {
    id: "jrn_06",
    childId: PARENT_CHILD.id,
    institution: SCHOOL,
    date: "2026-08-07",
    time: "16:00",
    isNew: false,
    tag: "활동일지",
    summary: "오후 자유놀이 시간에 비슷한 상황이 있었습니다.",
    detail: "오후 자유놀이 시간에 비슷한 상황이 있었습니다.",
  },
];

/** 케어 리포트 — 아이별로 따로 있다. 아직 데이터가 없는 아이는 key 자체가 없다. */
export const CARE_REPORTS: Record<string, { weekly: CareReport; monthly: CareReport }> = {
  child_1023: {
    weekly: {
      period: "weekly",
      rangeLabel: "08.15 부터 08.21 까지",
      summary: "이번 주에는 놀이를 스스로 마무리하는 모습이 늘었습니다. 기관 세 곳의 기록을 함께 봤습니다.",
      trendTitle: "변화 추이",
      trend: [
        { label: "1주", value: 5 },
        { label: "2주", value: 4 },
        { label: "3주", value: 2 },
        { label: "4주", value: 1 },
      ],
      trendInsight: "친구와 부딪히는 일이 4주 동안 조금씩 줄었습니다. 스스로 정리하는 모습이 늘었습니다.",
      trendEvidenceIds: ["jrn_01", "jrn_05", "jrn_06"],
      patterns: [
        { text: "오후 4시 자유놀이 시간에 비슷한 상황이 반복됩니다.", evidenceIds: ["jrn_04", "jrn_06"] },
      ],
      tips: [
        "놀이를 끝내기 5분 전에 미리 알려주세요.",
        "속상해할 때는 조용한 자리에서 2분 기다려주세요.",
        "정리를 함께 하며 마무리해주세요.",
      ],
    },
    monthly: {
      period: "monthly",
      rangeLabel: "08.01 부터 08.21 까지",
      summary: "8월 한 달 동안 놀이를 스스로 마무리하는 날이 꾸준히 늘었습니다. 기관 세 곳의 기록을 함께 봤습니다.",
      trendTitle: "변화 추이",
      trend: [
        { label: "1주", value: 5 },
        { label: "2주", value: 4 },
        { label: "3주", value: 2 },
        { label: "4주", value: 1 },
      ],
      trendInsight: "친구와 부딪히는 일이 4주 동안 조금씩 줄었습니다. 스스로 정리하는 모습이 늘었습니다.",
      trendEvidenceIds: ["jrn_01", "jrn_05", "jrn_06"],
      patterns: [
        { text: "오후 4시 자유놀이 시간에 비슷한 상황이 반복됩니다.", evidenceIds: ["jrn_04", "jrn_06"] },
      ],
      tips: [
        "놀이를 끝내기 5분 전에 미리 알려주세요.",
        "속상해할 때는 조용한 자리에서 2분 기다려주세요.",
        "정리를 함께 하며 마무리해주세요.",
      ],
    },
  },
};

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
