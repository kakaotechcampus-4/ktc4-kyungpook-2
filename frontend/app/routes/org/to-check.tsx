import { redirect, type ClientLoaderFunctionArgs } from "react-router";

export function clientLoader({ request }: ClientLoaderFunctionArgs) {
  const blocked = new URL(request.url).pathname.endsWith("/reinput");
  return redirect(blocked ? "/check?open=blocked" : "/check?open=confirm");
}

export default function ToCheck() {
  return null;
}
