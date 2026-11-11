import { redirect, type ClientLoaderFunctionArgs } from "react-router";

export function clientLoader({ request, params }: ClientLoaderFunctionArgs) {
  const path = new URL(request.url).pathname;
  if (path.startsWith("/gate2")) return redirect("/children?tab=approve");
  if (path === "/children/new") return redirect("/children?register=1");
  if (path.startsWith("/children/") && params.id) return redirect(`/children?child=${encodeURIComponent(params.id)}`);
  return redirect("/children");
}

export default function ToChildren() {
  return null;
}
