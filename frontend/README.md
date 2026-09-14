# Frontend (Vite)

잇다(ITDA) 기관 대시보드와 학부모 웹앱. React Router v8 **SPA 모드** 한 프로젝트에서
라우트로 두 사용자 화면을 나눕니다.

> 이 프로젝트는 기존 Next.js 앱(`../frontend`)을 이관한 것입니다. Next.js 고유 기능이
> `src/proxy.ts`(역할 게이트) 하나로 수렴했고, 나머지 페이지는 mock 함수를 부르는
> 것뿐이라 이관 비용이 낮았습니다. 이제 이 프로젝트가 실제로 개발되는 쪽이고,
> `../frontend`는 참고용으로만 남아있습니다.

## 스택

| 항목 | 선택 |
|---|---|
| 프레임워크 | React Router v8, **SPA 모드**(`ssr: false`) · Vite · TypeScript |
| 스타일 | Tailwind CSS v4 (`app/app.css` 의 `@theme` 이 토큰 단일 출처) |
| 서체 | 시스템 한글 폰트 — 웹폰트 없음 (Next 시절과 동일한 이유: 학부모 첫 화면 용량) |
| 데이터 | `app/lib/api.ts` 심(seam) + `app/lib/mock/data.ts` |
| 다자녀 상태 | `app/components/parent/ChildContext.tsx`(React Context) + `app/lib/selectedChild.ts`(localStorage) — 선택된 아이 id 를 보관 |
| 배포 | `output: "standalone"` → Docker → `infra/docker/compose.yaml` |

상태 관리 라이브러리(TanStack Query·zustand)는 아직 넣지 않았습니다.
실제 API 가 붙는 시점에 추가합니다. 다자녀 선택 상태만 React 기본 Context 로 관리합니다.

## 실행

```bash
npm install
npm run dev        # http://localhost:5173
npm run build
npm run typecheck   # react-router typegen && tsc
```

포트를 고정해뒀습니다(`vite.config.ts` · `strictPort: true`) — 5173 이 이미 쓰이고
있으면 자동으로 다른 포트로 넘어가지 않고 에러가 납니다. 이전에 띄운 dev 서버를
종료하고 다시 실행하세요.

## 화면

기관 대시보드는 데스크톱(1440), 학부모 웹앱은 모바일(375, `md`/`lg` 에서 카드
폭만 430→560→640px 로 확장) 기준입니다.

### 기관 (`/(org)`)

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
| `/children`, `/children/:id` | I-09 아동 관리 · Child Context |
| `/insights` | I-10 Insight 목록 |
| `/gate2` | I-11 Gate 2 발송 검토 |
| `/inbox` | I-12 수신함 |
| `/chat` | I-13 상담 도우미 |
| `/history` | I-14 활동 이력 |

기관 사이드바 메뉴명은 사용자 친화적으로 다시 지었습니다("확인 필요 큐"→"확인이
필요한 기록" 등). 라우트·기능은 위 표 그대로입니다.

### 학부모 (`/parent`)

| 경로 | 화면 |
|---|---|
| `/parent/invite` | P-01 초대 진입 · 본인 인증 (코드→휴대폰→OTP→약관 동의) |
| `/parent/consent` | P-02 확인 · 동의 (기관별 실제 공유 항목 표시) |
| `/parent/care-info` | 아이 상세정보 (온보딩 마지막 단계 겸 설정에서 재수정) |
| `/parent` | P-03 홈 — 오늘의 기록, TODAY 요약, 연결된 기관, 최근 공유 활동 |
| `/parent/timeline` | 타임라인 — 기관별 필터(`?institution=`), 일지 목록 |
| `/parent/journal/:id` | 일지 상세 — 이전/다음 일지 탐색, "이 내용이 이상해요" |
| `/parent/report` | 케어 리포트 — 주간/월간, 변화 추이, 반복 패턴, 오프라인 에러 상태 |
| `/parent/report/evidence` | 리포트 근거 일지 목록 |
| `/parent/notifications` | 알림 — 새 기관 권한 요청 승인/거절 |
| `/parent/settings` | P-04 설정 — 기관 권한 토글, 아이 정보, 기관 요청사항 바로가기 |
| `/parent/consent/manage/add` | 기관 직접 추가(코드 입력) |
| `/parent/institution-requests` | 기관 요청사항(준비물·확인사항) |
| `/parent/history` | P-05 활동 이력 (하단 탭에는 없음, 직접 접근 가능) |

하단 탭은 **홈 · 타임라인 · 리포트 · 설정** 4개입니다. 헤더 아바타를 누르면 아이
전환 드롭다운이 뜨는데, 아이가 1명뿐이면 화살표가 안 보여서 한 자녀 사용자는
기존과 동일하게 느낍니다.

## 지켜야 하는 UI 규칙

화면을 고칠 때 아래를 깨지 않는지 확인해주세요. 제품 원칙에서 직접 나온 것입니다.

1. **Gate 2 수신 기관 체크박스 기본값은 전체 해제.** 0곳 선택이면 발송 버튼은 비활성입니다.
2. 낮은 확신도 기록에 **자동 확정 버튼을 두지 않습니다.** 사람이 아이를 선택합니다.
3. 상담 도우미 답변에는 **항상 근거 출처**를 붙이고, 근거가 없으면 "확인된 기록에는 없어요"로 답합니다.
4. 아동 타임라인에서 **기록이 없는 날은 비워 표시**합니다. 추정치로 채우지 않습니다.
5. 수신함에는 원본이 아니라 **변환된 최소 정보**만 표시합니다.
6. 학부모 화면에 **자유질의 챗봇을 넣지 않습니다.**
7. 미동의 기관은 **선택 불가 + 사유 표시**입니다. 선택 후 거부되는 방식은 쓰지 않습니다.
8. 동의 회수/권한 끄기 화면에는 **"이미 보낸 정보는 회수되지 않는다"** 는 문구가 반드시 있어야 합니다.
9. 상태는 색 단독으로 전달하지 않습니다. **텍스트 라벨을 항상 함께** 씁니다.
10. 기관 화면 본문은 16px 이상, 클릭 타깃은 44px 이상 (`tap` 유틸).
11. **아이별 데이터는 반드시 childId 로 조회합니다.** 리포트·기관 요청사항·타임라인·알림 배지 등 어떤 것도 "현재 선택된 아이" 를 빼먹고 전역 데이터를 그대로 돌려주면 안 됩니다 — 다자녀일 때 다른 아이 데이터가 섞이는 사고로 이어집니다.

## 역할 경계

Next.js 시절엔 `src/proxy.ts`(서버 미들웨어)가 기관 경로와 `/parent` 경로를 렌더
전에 분리했습니다. SPA 에는 서버가 없어서, 대신 각 레이아웃 라우트의
**`clientLoader`** 가 그 역할을 합니다.

- `app/routes/org/layout.tsx` — role !== "org" 면 `/login` 으로 리다이렉트
- `app/routes/parent/guard.tsx` — role !== "parent" 면 `/parent/invite` 로 리다이렉트

`clientLoader` 가 끝나기 전까지는 `app/root.tsx` 의 **`HydrateFallback`**(로딩 화면)만
보이고, 화면(`children`)은 절대 먼저 그려지지 않습니다 — 서버가 0ms 에 끊어주던 것을
클라이언트 로딩 한 박자로 대체한 것이라, "다른 역할 화면이 순간 노출"되는 문제는
여전히 없습니다. 

역할 판정은 지금 `localStorage`(`itda_role`, `app/lib/auth.ts`)만 봅니다. Spring Boot
인증이 붙으면 `getSession()`/`signIn()`/`signOut()` 내부만 실제 API 호출로 바꾸면
됩니다 — 호출부(각 라우트의 `clientLoader`)는 그대로 둡니다.

## API 연결

`app/lib/api.ts` 의 함수 본문만 바꾸면 됩니다. 화면 코드는 그대로 둡니다.

```
VITE_USE_MOCK=false   # mock 끄기
VITE_API_BASE_URL=http://backend:8080
```

`VITE_*` 는 빌드 시점에 번들에 박히므로, 값이 바뀌면 다시 빌드해야 합니다.

`lib/api.ts` 상단 주석에 실제 백엔드 계약(카카오 OAuth + JWT, RawRecord S3 저장 등)이
기획서와 다른 부분이 정리돼 있습니다 — 연결 전에 꼭 읽어보세요.
