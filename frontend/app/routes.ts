import { type RouteConfig, index, layout, route } from "@react-router/dev/routes";

export default [
  index("routes/root-redirect.tsx"),
  route("login", "routes/login.tsx"),
  route("oauth/success", "routes/oauth-success.tsx"),
  route("signup", "routes/signup.tsx"),
  // 기관 약관(I-16). 기관 가드가 동의하지 않은 기관을 이리 보내므로 기관 묶음 밖에 둔다.
  route("org-terms", "routes/org-terms.tsx"),

  layout("routes/org/layout.tsx", [
    route("dashboard", "routes/org/dashboard.tsx"),
    route("upload", "routes/org/upload.tsx"),
    route("queue/matching", "routes/org/queue-matching.tsx"),
    route("queue/reinput", "routes/org/queue-reinput.tsx"),
    route("check", "routes/org/check.tsx"),
    route("gate1", "routes/org/gate1.tsx"),
    route("gate2", "routes/org/gate2.tsx"),
    route("children", "routes/org/children.tsx"),
    route("children/new", "routes/org/children-new.tsx"),
    route("children/:id", "routes/org/children-detail.tsx"),
    route("checklists", "routes/org/checklists.tsx"),
    route("insights", "routes/org/insights.tsx"),
    route("inbox", "routes/org/inbox.tsx"),
    route("inbox/:id", "routes/org/inbox-detail.tsx"),
  ]),

  // 학부모: shell 은 공개(초대·동의 온보딩 포함), guard 는 그 안에서 role==parent 만 통과시킨다.
  layout("routes/parent/shell.tsx", [
    route("parent/invite", "routes/parent/invite.tsx"),
    route("parent/consent", "routes/parent/consent.tsx"),
    layout("routes/parent/guard.tsx", [
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
] satisfies RouteConfig;
