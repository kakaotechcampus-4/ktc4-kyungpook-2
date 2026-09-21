# Frontend (Vite)

잇다(ITDA) 기관 대시보드와 학부모 웹앱. React Router v8 **SPA 모드** 한 프로젝트에서
라우트로 두 사용자 화면을 나눕니다.

## 스택

| 항목 | 선택 |
|---|---|
| 프레임워크 | React Router v8, **SPA 모드**(`ssr: false`) · Vite · TypeScript |
| 스타일 | Tailwind CSS v4 (`app/app.css` 의 `@theme` 이 토큰 단일 출처) |
| 서체 | 시스템 한글 폰트 — 웹폰트 없음 (Next 시절과 동일한 이유: 학부모 첫 화면 용량) |
| 데이터 | `app/lib/api.ts` 심(seam) + `app/lib/mock/data.ts` |
| 다자녀 상태 | `app/components/parent/ChildContext.tsx`(React Context) + `app/lib/selectedChild.ts`(localStorage) — 선택된 아이 id 를 보관 |
| 배포 | Vite 정적 빌드 → nginx 이미지(`Dockerfile`) → `infra/docker/compose.yaml` |

상태 관리 라이브러리(TanStack Query·zustand)는 아직 넣지 않았습니다.
실제 API 가 붙는 시점에 추가합니다. 다자녀 선택 상태만 React 기본 Context 로 관리합니다.

## 실행

```bash
npm install
npm run dev        # http://localhost:3000
npm run build
npm run typecheck   # react-router typegen && tsc
```

포트를 고정해뒀습니다(`vite.config.ts` · `strictPort: true`) — 3000 이 이미 쓰이고
있으면 자동으로 다른 포트로 넘어가지 않고 에러가 납니다. 이전에 띄운 dev 서버를
종료하고 다시 실행하세요.

포트가 3000 인 이유는 백엔드가 로그인 후 돌려보내는 주소
(`app.auth.success-redirect` = `http://localhost:3000/oauth/success`)와 compose 의
frontend 컨테이너 포트가 모두 3000 이기 때문입니다. 백엔드 CORS 허용 오리진도 3000 입니다.
그래서 Docker 를 띄운 채로는 dev 서버가 포트 충돌로 뜨지 않습니다 —
`docker compose stop frontend` 로 비우고 실행하세요.

### Docker 로 전체 스택 실행

`npm run dev` 는 프론트엔드만 띄웁니다. 백엔드·DB 까지 함께 띄우려면:

```bash
cp infra/docker/.env.example infra/docker/.env   # POSTGRES_PASSWORD 를 채운다
touch AI/.env                                    # 비어 있어도 되지만 파일은 있어야 한다
cd infra/docker && docker compose up -d --build
```

접속 주소는 **http://localhost** 입니다(nginx 80). 개발 서버의 3000 과 달리
포트를 붙이지 않습니다. `/api/` 는 nginx 가 backend 로 넘깁니다.

## 화면

기관 대시보드는 데스크톱(1440), 학부모 웹앱은 모바일(375, `md`/`lg` 에서 카드
폭만 430→560→640px 로 확장) 기준입니다.

### 기관 (`/(org)`)

| 경로 | 화면 |
|---|---|
| `/login` | I-01 로그인 (카카오) |
| `/oauth/success` | 로그인 후 착지 지점 — 화면 없이 역할에 맞는 첫 화면으로 보냅니다 |
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

역할 판정은 지금 `localStorage`(`itda_role`, `app/lib/auth.ts`)만 봅니다. 백엔드에
사용자 테이블이 없어 JWT 의 subject 가 kakaoId 뿐이고, 서버가 기관/학부모를 구분할
방법이 없기 때문입니다. `GET /api/v1/auth/me`(역할 포함)가 열리면
`getSession()`/`grantRole()` 내부만 그 응답으로 바꾸면 됩니다 — 호출부(각 라우트의
`clientLoader`)는 그대로 둡니다.

## 환경 변수

`.env.example` 을 `.env` 로 복사해서 씁니다 (`.env` 는 gitignore 됩니다).

```bash
cp .env.example .env
```

| 변수 | 기본 | 설명 |
|---|---|---|
| `VITE_USE_MOCK` | `true` | 데이터(`lib/api.ts`). `false` 일 때만 실제 HTTP 호출 |
| `VITE_AUTH_MOCK` | `true` | 로그인(`lib/auth.ts`). 데이터와 분리돼 있습니다 |
| `VITE_AUTH_ORIGIN` | 빈 값 | 로그인 진입 주소의 오리진. **dev 는 `http://localhost:8080`** |
| `VITE_API_BASE_URL` | 빈 값 | dev proxy 와 nginx 가 같은 오리진의 `/api` 를 넘기므로 비워 둡니다 |

**카카오 키는 프론트에 없습니다.** `client_id` · `client_secret` 모두 백엔드만 가집니다.
로그인 전 과정을 백엔드가 처리하기 때문입니다(아래 "로그인" 참고).

**`VITE_AUTH_ORIGIN` 이 dev 에서만 필요한 이유** — Spring 이 **자기가 받은 주소**로
`redirect_uri` 를 조립합니다. Vite 프록시를 거치면 그 값이 카카오 콘솔 등록값과 어긋나
KOE006 으로 거절당합니다. 그래서 로그인 진입만 백엔드(8080)로 직접 보냅니다.
출입증 쿠키는 포트를 구분하지 않으므로 3000 에서 그대로 쓸 수 있습니다.
운영은 nginx 가 같은 오리진의 `/oauth2/` 를 백엔드로 넘기므로 비워 둡니다.

**mock 스위치를 둘로 나눈 이유** — 백엔드에 열려 있는 것이 인증과 원본 기록뿐이라,
하나로 묶으면 로그인을 켜는 순간 대시보드·아이 목록·게이트가 전부 빈 화면이 됩니다.
`VITE_AUTH_MOCK=false`, `VITE_USE_MOCK=true` 로 두면 **로그인만 실연동**하고
나머지 화면은 mock 으로 유지할 수 있습니다.

`VITE_AUTH_MOCK=true` 일 때는 로그인 화면에 "mock 데이터로 둘러보기" 버튼이 나옵니다.
백엔드 없이 기관 화면을 확인하는 용도이고, 실연동 빌드에서는 렌더링되지 않습니다.

`VITE_*` 는 빌드 시점에 번들에 박히므로, 값을 바꾸면 **dev 서버를 다시 시작**하거나
이미지를 다시 빌드해야 합니다.

## 로그인

**로그인은 백엔드가 전부 맡습니다.** Spring Security `oauth2Login` 이 인가 요청(state
포함) · 카카오 토큰 교환 · 사용자 조회까지 처리하고, 끝나면 출입증을 httpOnly 쿠키로
심은 뒤 프론트로 돌려보냅니다. 프론트가 인가 코드나 토큰을 직접 만지는 부분은 없습니다.

```
/login  ──버튼──▶  (BE) /oauth2/authorization/kakao  ──▶  카카오
                                                            │
        ◀── /oauth/success ──  (BE) /login/oauth2/code/kakao ◀┘
             (실패 시 /login)      쿠키 발급
```

프론트가 하는 일은 두 가지뿐입니다.

1. 로그인 버튼이 `/oauth2/authorization/kakao` 로 **페이지를 이동**시킵니다
   (fetch 가 아닙니다 — 사용자가 카카오 도메인에서 직접 로그인해야 합니다)
2. `/oauth/success` 가 착지 지점입니다. 화면을 그리지 않고 역할에 맞는 첫 화면으로 보냅니다

**출입증은 `access_token` httpOnly 쿠키입니다.** 브라우저가 자동으로 붙이므로
`Authorization` 헤더를 만들지 않습니다. JS 가 읽을 수 없어 XSS 로도 훔칠 수 없습니다.

**대신 CSRF 대비가 필요합니다.** 쿠키는 남의 사이트에서 띄운 폼에도 자동으로 실리기
때문입니다. 백엔드가 `XSRF-TOKEN` 쿠키(이것만은 httpOnly 가 아닙니다)를 내려주고,
`lib/api.ts` 가 쓰기 요청마다 `X-XSRF-TOKEN` 헤더로 되돌려보냅니다. 없으면 403 입니다.

**로그아웃도 서버에 부탁해야 합니다** — httpOnly 라 프론트가 쿠키를 지울 수 없습니다.
`signOut()` 이 `POST /api/v1/auth/logout` 을 부르면 서버가 만료된 쿠키를 다시 심습니다.

**401 을 받으면 로컬 역할 표시를 지웁니다.** 쿠키 만료를 프론트가 볼 수 없어서,
그냥 두면 로그인된 척하며 요청마다 401 만 맞는 상태가 됩니다.

## API 연결

`app/lib/api.ts` 의 함수 본문만 바꾸면 됩니다. 화면 코드는 그대로 둡니다.

`lib/api.ts` 상단 주석에 실제 백엔드 계약(카카오 OAuth + JWT, RawRecord S3 저장 등)이
기획서와 다른 부분이 정리돼 있습니다 — 연결 전에 꼭 읽어보세요.

## 관련 문서

| 문서 | 역할 |
|---|---|
| [docs/frontend/feature-interfaces.md](../docs/frontend/feature-interfaces.md) | 도메인 타입·API 함수 계약 (팀 공유용) |
| [docs/frontend/screen-specs.md](../docs/frontend/screen-specs.md) | 화면별 기능 명세 |
| [docs/frontend/feature-spec.md](../docs/frontend/feature-spec.md) | 전체 기능 명세·상태 전이·제품 규칙 |
| [docs/api/api-spec.md](../docs/api/api-spec.md) | 백엔드 요청용 API 명세 |
| [docs/api/api-conventions.md](../docs/api/api-conventions.md) | 응답 래퍼·오류 코드·HTTP 상태 규약 |
