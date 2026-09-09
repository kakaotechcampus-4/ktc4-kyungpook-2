"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";

/**
 * 초안용 세션. SMS OTP 는 아직 붙지 않았고, 역할 쿠키만 심는다.
 * Spring Boot 인증이 붙으면 여기서 토큰을 받아 httpOnly 쿠키로 저장한다
 * (BFF 패턴 — access token 을 브라우저 JS 에 노출하지 않는다).
 */
export async function signIn(role: "org" | "parent") {
  const jar = await cookies();
  jar.set("itda_role", role, {
    httpOnly: true,
    sameSite: "lax",
    path: "/",
    maxAge: 60 * 60 * 8,
  });
  redirect(role === "org" ? "/dashboard" : "/parent");
}

export async function signOut() {
  const jar = await cookies();
  jar.delete("itda_role");
  redirect("/login");
}
