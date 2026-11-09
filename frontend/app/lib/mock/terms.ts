import type { TermsDocument } from "@/lib/auth";

const VERSION = "2026-11-07";
const PRIVACY_VERSION = "2026-11-09";

function doc(key: TermsDocument["key"], title: string, body: string, version = VERSION): TermsDocument {
  return { key, title, version, effectiveDate: version, draft: true, body };
}

export const MOCK_TERMS: TermsDocument[] = [
  doc(
    "tos",
    "서비스 이용약관",
    "제1조(목적)\n이 약관은 잇다(이하 \"서비스\")를 보호자가 이용하는 조건과 절차를 정합니다.\n\n제2조(서비스 내용)\n- 보호자는 선생님이 확인한 일지와 기관이 확인한 리포트만 봅니다.\n- 일지와 리포트는 AI 가 만든 글을 사람이 확인한 것이지만, 틀리거나 빠진 내용이 있을 수 있습니다.\n\n(mock 본문입니다. 실제 문안은 서버가 줍니다.)",
  ),
  doc(
    "privacy",
    "개인정보와 민감정보 수집 · 이용",
    "1. 일반 개인정보\n- 보호자: 카카오 회원번호\n- 아이: 이름, 생년월일\n\n2. 민감정보\n- 아이의 알레르기, 복용약\n\n보유 기간: [보유 기간: 팀 확인 필요]\n\n(mock 본문입니다. 실제 문안은 서버가 줍니다.)",
    PRIVACY_VERSION,
  ),
  doc(
    "org_tos",
    "기관 서비스 이용약관",
    "제1조(목적)\n이 약관은 기관이 잇다에 기록을 올리고 확인하는 조건을 정합니다.\n\n제2조(기관의 확인 책임)\n- 보호자에게 열리는 글은 기관 담당자가 확인한 글입니다.\n\n(mock 본문입니다. 실제 문안은 서버가 줍니다.)",
  ),
  doc(
    "org_privacy",
    "담당자 개인정보 수집 · 이용",
    "수집 항목: 카카오 회원번호, 소속 기관\n이용 목적: 로그인과 기관 화면 이용\n\n(mock 본문입니다. 실제 문안은 서버가 줍니다.)",
    PRIVACY_VERSION,
  ),
  doc(
    "org_child_data",
    "아동 정보 보호 서약",
    "- 보호자가 공유한 아이 정보는 돌봄과 교육에만 씁니다.\n- 다른 기관이나 밖으로 옮기지 않습니다.\n\n(mock 본문입니다. 실제 문안은 서버가 줍니다.)",
  ),
];
