import { type RouteConfig, index, layout, route } from "@react-router/dev/routes";

export default [
  index("routes/root-redirect.tsx"),
  route("login", "routes/login.tsx"),
  route("oauth/success", "routes/oauth-success.tsx"),
  route("signup", "routes/signup.tsx"),
  // 기관 약관(I-16). 기관 가드가 동의하지 않은 기관을 이리 보내므로 기관 묶음 밖에 둔다.
  route("org-terms", "routes/org-terms.tsx"),
  // 약관 다시 읽기. 로그인 없이 열고 역할 묶음 밖에 둔다(가드가 동의를 요구하면 동의 전에 못 읽는다).
  route("terms/:key", "routes/terms.tsx"),

  layout("routes/org/layout.tsx", [
    // 화면을 옮길 때마다 로그인이 풀렸는지 본다 — 가드는 같은 레이아웃 안 이동에서 다시 돌지 않는다
    layout("routes/org/session.tsx", [
      route("dashboard", "routes/org/dashboard.tsx"),
      route("check", "routes/org/check.tsx"),
      route("gate1", "routes/org/gate1.tsx"),
      route("children", "routes/org/children.tsx"),
      route("checklists", "routes/org/checklists.tsx"),
      // 옛 주소 → 새 화면 (북마크·알림 링크 보호)
      route("upload", "routes/org/to-dashboard.tsx", { id: "old-upload" }),
      route("upload/:id", "routes/org/to-dashboard.tsx", { id: "old-upload-item" }),
      route("queue/matching", "routes/org/to-check.tsx", { id: "old-queue-matching" }),
      route("queue/reinput", "routes/org/to-check.tsx", { id: "old-queue-reinput" }),
      route("gate2", "routes/org/to-children.tsx", { id: "old-gate2" }),
      route("insights", "routes/org/to-children.tsx", { id: "old-insights" }),
      route("inbox", "routes/org/to-children.tsx", { id: "old-inbox" }),
      route("inbox/:id", "routes/org/to-children.tsx", { id: "old-inbox-item" }),
      route("children/new", "routes/org/to-children.tsx", { id: "old-children-new" }),
      route("children/:id", "routes/org/to-children.tsx", { id: "old-children-item" }),
    ]),
  ]),

  // 학부모: shell 은 공개(초대·동의 온보딩 포함), guard 는 그 안에서 role==parent 만 통과시킨다.
  layout("routes/parent/shell.tsx", [
    route("parent/invite", "routes/parent/invite.tsx"),
    route("parent/consent", "routes/parent/consent.tsx"),
    layout("routes/parent/guard.tsx", [
      // 화면을 옮길 때마다 로그인이 풀렸는지 본다 — 가드는 홈 · 리포트로 들어갈 때만 다시 돈다
      layout("routes/parent/session.tsx", [
        route("parent", "routes/parent/home.tsx"),
        route("parent/timeline", "routes/parent/timeline.tsx"),
        route("parent/journal/:id", "routes/parent/journal-detail.tsx"),
        route("parent/report", "routes/parent/report.tsx"),
        route("parent/report/:id", "routes/parent/report-detail.tsx"),
        route("parent/notifications", "routes/parent/notifications.tsx"),
        route("parent/checklists", "routes/parent/checklists.tsx"),
        route("parent/settings", "routes/parent/settings.tsx"),
        route("parent/settings/shares/:institutionId", "routes/parent/settings-shares.tsx"),
        route("parent/care-info", "routes/parent/care-info.tsx"),
        route("parent/consent/manage", "routes/parent/consent-manage.tsx"),
        route("parent/consent/manage/add", "routes/parent/institution-add.tsx"),
      ]),
    ]),
  ]),

  // 라우트 표에 없는 주소 (#444). 맞는 라우트가 없으면 브라우저가 첫 렌더부터 오류 화면을 그려, 빌드가 미리 만든
  // index.html(로딩 화면)과 달라 하이드레이션 오류(React #418)가 난다. 여기서 받아 로딩 화면 → 404 순서로 그린다.
  // 라우트 순위가 가장 뒤라 있는 주소 · 옛 주소 이동을 가로채지 않는다.
  route("*", "routes/not-found.tsx"),
] satisfies RouteConfig;
