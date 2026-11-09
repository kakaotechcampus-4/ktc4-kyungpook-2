import { data } from "react-router";

/**
 * 라우트 표에 없는 주소 (#444). 늘 404 를 던져 루트 ErrorBoundary 가 "페이지를 찾을 수 없습니다" 를 그린다.
 *
 * 라우트로 받는 이유 — SPA 모드는 빌드 때 로딩 화면(루트 HydrateFallback)이 그려진 index.html 하나를 미리 만든다.
 * 맞는 라우트가 없으면 브라우저가 첫 렌더부터 오류 화면을 그려 그 HTML 과 달라지고 React 하이드레이션 오류(#418)가
 * 난다. clientLoader 가 있으면 첫 렌더는 같은 로딩 화면이고, 로더가 던진 뒤 오류 화면으로 바뀐다.
 */
export function clientLoader(): never {
  throw data(null, { status: 404 });
}

/** 그려질 일 없다 — clientLoader 가 늘 던진다. */
export default function NotFound() {
  return null;
}
