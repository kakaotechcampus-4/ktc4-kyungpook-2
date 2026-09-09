import { NextResponse, type NextRequest } from "next/server";

/**
 * 역할 경계를 렌더 전에 끊는다.
 *
 * Next 16 에서 `middleware` 파일 규칙이 `proxy` 로 이름이 바뀌었다.
 *
 * 왜 여기서 끊는가 — SPA 로 만들면 화면이 한 번 그려진 뒤 리다이렉트하게 되고,
 * 그 사이 다른 역할의 화면이 순간 노출된다. 아동 관찰 기록을 다루는 서비스에서
 * 이건 버그가 아니라 접근 제어 위반이다. (기획서 제품 원칙 03)
 *
 * 세션 판정은 지금 쿠키 존재 여부만 본다. Spring Boot 인증이 붙으면
 * `verifySession()` 안쪽만 실제 검증으로 바꾼다 — 호출부는 그대로 둔다.
 */

const ORG_PREFIXES = [
  "/dashboard",
  "/upload",
  "/queue",
  "/gate1",
  "/gate2",
  "/children",
  "/insights",
  "/inbox",
  "/chat",
  "/history",
  "/settings",
];

const PARENT_PREFIX = "/parent";

/** 로그인 없이 들어올 수 있는 경로 */
const PUBLIC_PATHS = ["/login", "/parent/invite", "/parent/consent"];

type Role = "org" | "parent" | null;

function readRole(req: NextRequest): Role {
  const role = req.cookies.get("itda_role")?.value;
  if (role === "org" || role === "parent") return role;
  return null;
}

export function proxy(req: NextRequest) {
  const { pathname } = req.nextUrl;

  if (PUBLIC_PATHS.some((p) => pathname === p || pathname.startsWith(p + "/"))) {
    return NextResponse.next();
  }

  const role = readRole(req);
  const wantsOrg = ORG_PREFIXES.some((p) => pathname.startsWith(p));
  const wantsParent = pathname.startsWith(PARENT_PREFIX);

  if (wantsOrg && role !== "org") {
    return NextResponse.redirect(new URL("/login", req.url));
  }
  if (wantsParent && role !== "parent") {
    return NextResponse.redirect(new URL("/parent/invite", req.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
