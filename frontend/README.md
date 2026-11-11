# Frontend

잇다(ITDA)의 기관 대시보드와 보호자 웹앱입니다. React Router SPA 한 프로젝트에서 라우트로 두 화면을 나눕니다.

- 기관 화면은 데스크톱(1440px), 보호자 화면은 모바일(375px) 기준입니다
- 스택: React 19 · React Router 8 (SPA 모드) · TypeScript · Vite · Tailwind CSS 4 · Playwright

---

## 실행

```bash
npm install
```

```bash
npm run dev
```

**http://localhost:3000** 로 접속합니다. 기본값은 mock 데이터라 백엔드 없이 화면을 볼 수 있습니다.

- 포트는 3000으로 고정입니다. 이미 쓰이고 있으면 다른 포트로 넘어가지 않고 에러가 납니다. Docker로 전체 스택을 띄워 둔 상태라면 `docker compose stop frontend`로 비웁니다
- 실제 백엔드에 붙이려면 `.env`를 만들고 `VITE_USE_MOCK=false` · `VITE_AUTH_MOCK=false`로 바꿉니다. `/api` 요청은 `localhost:8080`으로 넘어갑니다

```bash
npm run typecheck
```

```bash
npm run build
```

### E2E 테스트 (Playwright)

실제 프론트와 실제 백엔드를 띄우고 브라우저로 핵심 흐름을 눌러 봅니다.

```bash
npx playwright install chromium
```

```bash
npm run e2e
```

- 백엔드(18080)와 프론트(13000)를 E2E 전용 포트로 직접 띄우고, 끝나면 끕니다. 개발 서버를 띄워 둔 채 돌려도 됩니다
- 시나리오: 기관 로그인 · 아이 등록 · 보호자 연결 · 파일 업로드 · 업로드 확인 창 · AI가 꺼졌을 때의 안내
- 실패하면 `npx playwright show-report`로 스크린샷과 trace를 봅니다
- AI 응답이 필요한 흐름(매칭 → 요약 → 승인)은 다루지 않습니다

---

## 화면

**기관** — `/dashboard` 처리 현황 · `/check` 확인이 필요한 기록 · `/gate1` 요약 검토 · `/children` 아이 관리 · `/checklists` 준비물 · 확인사항

**보호자** — `/parent/invite` 초대 코드 입력 · `/parent` 홈 · `/parent/timeline` 일지 · `/parent/report` 리포트 · `/parent/notifications` 알림 · `/parent/checklists` 준비물 · `/parent/settings` 설정 · `/parent/consent/manage` 기관 공유 관리 · `/parent/care-info` 돌봄 정보

**공통** — `/login` · `/signup` · `/withdrawal` 탈퇴 안내

예전 경로(`/upload`, `/queue/*`, `/gate2`, `/inbox` 등)는 지금 화면으로 넘겨 줍니다. 전체 목록은 [`app/routes.ts`](app/routes.ts)에 있습니다.

---

## 폴더

- `app/routes/` — 화면. `org/`는 기관, `parent/`는 보호자
- `app/components/` — 화면 조각. `org/` · `parent/` · `ui/`(공통)
- `app/lib/api.ts` — 백엔드 호출 함수. 화면은 이 파일만 거쳐 백엔드를 부릅니다
- `app/lib/auth.ts` — 로그인 상태
- `app/lib/mock/` — mock 데이터
- `e2e/` — Playwright 시나리오

---

## 로그인

로그인은 백엔드가 전부 맡습니다. 프론트는 카카오 키를 갖지 않습니다.

```
/login ─버튼─▶ (백엔드) /oauth2/authorization/kakao ─▶ 카카오
                                                        │
       ◀── /oauth/success ◀── (백엔드) 쿠키 발급 ◀──────┘
```

- 로그인 상태는 `access_token` httpOnly 쿠키입니다. 브라우저가 알아서 붙이므로 헤더를 만들지 않습니다
- 쓰기 요청에는 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 되돌려 보냅니다 (`api.ts`가 처리)
- 기관 · 보호자 화면은 들어갈 때마다 `/api/v1/auth/me`로 역할을 묻고, 맞지 않으면 다른 화면으로 보냅니다. 확인이 끝나기 전에는 화면을 그리지 않습니다

---

## 지켜야 하는 규칙

- **아이별 데이터는 반드시 `childId`로 조회합니다.** 다자녀 보호자에게 다른 아이 데이터가 섞이면 안 됩니다
- 상태는 색만으로 전달하지 않고 글자 라벨을 함께 씁니다
- 기관 화면 본문은 16px 이상, 누르는 곳은 44px 이상입니다 (`tap` 유틸)
