# Frontend

잇다(ITDA) 기관 대시보드와 학부모 웹앱. Next.js App Router 한 프로젝트에서
라우트로 두 사용자 화면을 나눕니다.

## 스택

| 항목 | 선택 |
|---|---|
| 프레임워크 | Next.js 16 (App Router) · TypeScript |
| 스타일 | Tailwind CSS v4 (`src/app/globals.css` 의 `@theme` 이 토큰 단일 출처) |
| 서체 | 시스템 한글 폰트 — 웹폰트 없음 |
| 데이터 | `src/lib/api` 심(seam) + `src/lib/mock` |
| 배포 | `output: "standalone"` → Docker → `infra/docker/compose.yaml` |

상태 관리 라이브러리(TanStack Query·zustand)는 아직 넣지 않았습니다.
실제 API 가 붙는 시점에 추가합니다.

## 실행

```bash
npm install
npm run dev        # http://localhost:3000
npm run build
npm run typecheck
```

## 화면

기관 대시보드는 데스크톱(1440), 학부모 웹앱은 모바일(375) 기준입니다.

| 경로 | 화면 |
|---|---|
| `/login` | I-01 로그인 (SMS OTP) |
| `/settings/org` | I-02 기관 등록 · 증빙서류 |
| `/dashboard` | I-03 처리 현황 |
| `/children/new` | I-04 아이 등록 · 초대코드 |
| `/upload` | I-05 기록 업로드 |
| `/queue/matching` | I-06 확인 필요 큐 |
| `/queue/reinput` | I-07 재입력 요청 큐 (BLOCK) |
| `/gate1` | I-08 Gate 1 요약 검토 |
| `/children`, `/children/[id]` | I-09 아동 관리 · Child Context |
| `/insights` | I-10 Insight 목록 |
| `/gate2` | I-11 Gate 2 발송 검토 |
| `/inbox` | I-12 수신함 |
| `/chat` | I-13 상담 도우미 |
| `/history` | I-14 활동 이력 |
| `/parent/invite` | P-01 초대 진입 · 본인 인증 |
| `/parent/consent` | P-02 확인 · 동의 |
| `/parent` | P-03 홈 |
| `/parent/consent/manage` | P-04 동의 관리 · 회수 |
| `/parent/history` | P-05 활동 이력 |

## 지켜야 하는 UI 규칙

화면을 고칠 때 아래를 깨지 않는지 확인해주세요. 제품 원칙에서 직접 나온 것입니다.

1. **Gate 2 수신 기관 체크박스 기본값은 전체 해제.** 0곳 선택이면 발송 버튼은 비활성입니다.
2. 낮은 확신도 기록에 **자동 확정 버튼을 두지 않습니다.** 사람이 아이를 선택합니다.
3. 상담 도우미 답변에는 **항상 근거 출처**를 붙이고, 근거가 없으면 "확인된 기록에는 없어요"로 답합니다.
4. 아동 타임라인에서 **기록이 없는 날은 비워 표시**합니다. 추정치로 채우지 않습니다.
5. 수신함에는 원본이 아니라 **변환된 최소 정보**만 표시합니다.
6. 학부모 화면에 **자유질의 챗봇을 넣지 않습니다.**
7. 미동의 기관은 **선택 불가 + 사유 표시**입니다. 선택 후 거부되는 방식은 쓰지 않습니다.
8. 동의 회수 화면에는 **"이미 보낸 정보는 회수되지 않는다"** 는 문구가 반드시 있어야 합니다.
9. 상태는 색 단독으로 전달하지 않습니다. **텍스트 라벨을 항상 함께** 씁니다.
10. 기관 화면 본문은 16px 이상, 클릭 타깃은 44px 이상 (`tap` 유틸).

## 역할 경계

`src/proxy.ts` 가 기관 경로와 `/parent` 경로를 **렌더 전에** 분리합니다.
클라이언트에서 리다이렉트하면 다른 역할 화면이 순간 노출될 수 있어서, 이 판정은
서버에서 해야 합니다. 현재는 `itda_role` 쿠키 존재만 확인하고, Spring Boot 인증이
붙으면 그 안쪽만 실제 검증으로 바꿉니다.

## API 연결

`src/lib/api/index.ts` 의 함수 본문만 바꾸면 됩니다. 화면 코드는 그대로 둡니다.

```
NEXT_PUBLIC_USE_MOCK=false   # mock 끄기
API_BASE_URL=http://backend:8080
```

`NEXT_PUBLIC_*` 는 빌드 시점에 번들에 박히므로, 값이 바뀌면 이미지를 다시 빌드해야 합니다.
