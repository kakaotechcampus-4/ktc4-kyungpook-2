/**
 * OAuth 2.0 `state` — 로그인 요청과 돌아온 인가 코드를 같은 브라우저로 묶는다.
 *
 * 없으면 이런 공격이 통한다: 공격자가 자기 카카오 계정의 인가 코드를 발급받아 두고,
 * 피해자 브라우저가 `?code=<공격자코드>` 로 콜백에 들어가게 유도한다. 피해자는
 * 로그인한 줄 알지만 실제로는 **공격자 계정**에 묶여, 이후 올리는 정보가 공격자에게
 * 쌓인다. (RFC 9700 §2.1, 카카오 REST API 로그인 문서)
 *
 * 저장소가 sessionStorage 인 이유:
 *  - 탭 단위라 로그인을 시작한 그 브라우저·그 탭에만 존재한다 = "브라우저에 묶인다"
 *  - localStorage 와 달리 탭을 닫으면 사라져, 쓰다 만 state 가 남지 않는다
 *
 * ⚠️ 이건 **클라이언트 측 검증**이다. 서버도 자기가 발급한 state 인지 확인하는 편이
 *    더 강하지만, 지금 BE(`POST /api/v1/auth/kakao`)는 `code` 만 받는다.
 *    BE 가 state 를 받게 되면 이 파일의 두 함수만 (서버 발급 값을 읽어오도록)
 *    교체하면 되고, 호출부는 그대로 둔다.
 */

const STATE_KEY = "kakao_oauth_state";

/** 32바이트 난수를 base64url 로. 예측 가능한 Math.random() 을 쓰면 의미가 없다. */
function randomState(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** 인가 요청 직전에 부른다. 발급과 동시에 저장하므로 저장을 빠뜨릴 수 없다. */
export function issueState(): string {
  const state = randomState();
  try {
    sessionStorage.setItem(STATE_KEY, state);
  } catch {
    // 프라이빗 모드 등으로 저장이 막히면 콜백에서 검증이 실패해 로그인이 중단된다.
    // 조용히 통과시키는 것보다 막히는 편이 안전하다.
  }
  return state;
}

/**
 * 콜백에서 부른다. 성공하든 실패하든 **반드시 지운다** —
 * 남겨두면 같은 state 로 다시 들어오는 재생(replay)이 가능해진다.
 */
export function consumeState(received: string | null): boolean {
  let saved: string | null = null;
  try {
    saved = sessionStorage.getItem(STATE_KEY);
    sessionStorage.removeItem(STATE_KEY);
  } catch {
    return false;
  }
  return Boolean(saved) && Boolean(received) && saved === received;
}
