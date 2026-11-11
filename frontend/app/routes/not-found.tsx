import { data } from "react-router";

export function clientLoader(): never {
  throw data(null, { status: 404 });
}

export default function NotFound() {
  return null;
}
