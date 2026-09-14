import { type RouteConfig, index, layout, route } from "@react-router/dev/routes";

export default [
  index("routes/root-redirect.tsx"),
  route("login", "routes/login.tsx"),

  layout("routes/org/layout.tsx", [
    route("dashboard", "routes/org/dashboard.tsx"),
    route("upload", "routes/org/upload.tsx"),
    route("queue/matching", "routes/org/queue-matching.tsx"),
    route("queue/reinput", "routes/org/queue-reinput.tsx"),
    route("gate1", "routes/org/gate1.tsx"),
    route("gate2", "routes/org/gate2.tsx"),
    route("children", "routes/org/children.tsx"),
    route("children/new", "routes/org/children-new.tsx"),
    route("children/:id", "routes/org/children-detail.tsx"),
    route("insights", "routes/org/insights.tsx"),
    route("inbox", "routes/org/inbox.tsx"),
    route("chat", "routes/org/chat.tsx"),
    route("history", "routes/org/history.tsx"),
    route("settings/org", "routes/org/settings.tsx"),
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
      route("parent/report/evidence", "routes/parent/report-evidence.tsx"),
      route("parent/notifications", "routes/parent/notifications.tsx"),
      route("parent/institution-requests", "routes/parent/institution-requests.tsx"),
      route("parent/settings", "routes/parent/settings.tsx"),
      route("parent/care-info", "routes/parent/care-info.tsx"),
      route("parent/consent/manage", "routes/parent/consent-manage.tsx"),
      route("parent/consent/manage/add", "routes/parent/institution-add.tsx"),
      route("parent/history", "routes/parent/history.tsx"),
    ]),
  ]),
] satisfies RouteConfig;
