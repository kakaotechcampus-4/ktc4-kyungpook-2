import { type RouteConfig, index, layout, route } from "@react-router/dev/routes";

export default [
  index("routes/root-redirect.tsx"),
  route("login", "routes/login.tsx"),
  route("oauth/success", "routes/oauth-success.tsx"),
  route("signup", "routes/signup.tsx"),
  route("org-terms", "routes/org-terms.tsx"),
  route("terms/:key", "routes/terms.tsx"),
  route("withdrawal", "routes/withdrawal.tsx"),

  layout("routes/org/layout.tsx", [
    layout("routes/org/session.tsx", [
      route("dashboard", "routes/org/dashboard.tsx"),
      route("check", "routes/org/check.tsx"),
      route("gate1", "routes/org/gate1.tsx"),
      route("children", "routes/org/children.tsx"),
      route("checklists", "routes/org/checklists.tsx"),
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

  layout("routes/parent/shell.tsx", [
    route("parent/invite", "routes/parent/invite.tsx"),
    route("parent/consent", "routes/parent/consent.tsx"),
    layout("routes/parent/guard.tsx", [
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

  route("*", "routes/not-found.tsx"),
] satisfies RouteConfig;
