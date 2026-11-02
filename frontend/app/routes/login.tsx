import { Link, useNavigate } from "react-router";
import { grantRole, isAuthMock, startKakaoLogin } from "@/lib/auth";
import "./login.css";

/**
 * 기관 담당자 로그인 진입점.
 *
 * 기획서 12절의 SMS OTP 는 폐기됐다 — 인증은 기관·보호자 모두 카카오 하나로 통일했다.
 *
 * 버튼은 백엔드의 `/oauth2/authorization/kakao` 로 **페이지를 이동시킨다**. 그 뒤로는
 * 전부 백엔드 몫이다 — state 발급, 카카오 인가, 토큰 교환, 출입증 쿠키 발급까지.
 * 끝나면 `/oauth/success` 로 돌아온다(실패하면 이 화면으로). 처음 로그인한 사람은
 * 거기서 회원가입(`/signup`)으로 넘어가 역할을 고른다.
 *
 * 보호자도 같은 카카오 로그인을 타지만 진입 화면은 `/parent/invite` 로 따로 둔다 —
 * 보호자에게 맞는 안내 문구로 시작하기 위해서다.
 *
 * 모양은 디자인 캔버스의 랜딩을 옮긴 것이다(`login.css`). 색은 그 파일 안에서만
 * 사는 변수라 **다른 화면은 영향을 받지 않는다** — 전역 팔레트 교체는 따로 할 일이다.
 * 공용 `KakaoLoginButton` 은 각진 버튼 모양이라 이 화면에서는 쓰지 않고, 같은
 * `startKakaoLogin()` 을 직접 부른다 — 보이는 모양만 다르고 동작은 완전히 같다.
 */
export default function LoginPage() {
  const navigate = useNavigate();
  const mock = isAuthMock();

  return (
    <main className="land">
      <div className="blobs" aria-hidden>
        <i style={{ left: -140, top: 120, width: 380, height: 380, background: "#CFEFDF", opacity: 0.7 }} />
        <i style={{ right: -120, top: -60, width: 360, height: 360, background: "#FFE9A8", opacity: 0.7 }} />
        <i style={{ left: -80, bottom: -120, width: 420, height: 420, background: "#E6DAFA", opacity: 0.6 }} />
        <i style={{ right: -60, bottom: 40, width: 380, height: 380, background: "#FFDCCF", opacity: 0.6 }} />
      </div>

      <div className="inner">
        <Ring />

        <button type="button" className="kakao" onClick={() => startKakaoLogin("org")}>
          <KakaoMark />
          카카오 로그인하기
          <Arrow />
        </button>

        <p className="lede">아이와 기관의 정보를 연결해 더 나은 돌봄과 더 넓은 가능성을 만듭니다.</p>

        {/*
          데모용 진입. 백엔드 없이 기관 화면을 볼 수 있어야 하는데, 역할을 주는 곳이
          로그인 성공 페이지뿐이라 이것이 없으면 대시보드에 들어갈 방법이 사라진다.
          실연동 빌드(VITE_AUTH_MOCK=false)에서는 렌더링되지 않는다.
        */}
        {mock ? (
          <div className="demo">
            <span className="or">데모</span>
            <button
              type="button"
              onClick={() => {
                grantRole("org");
                navigate("/dashboard");
              }}
            >
              mock 데이터로 둘러보기
            </button>
          </div>
        ) : null}

        <div className="fine">
          <p>
            기관 계정은 <b>승인된 선생님</b>만 만들 수 있어요
          </p>
          <p>
            학부모이신가요? <Link to="/parent/invite">보호자 화면으로 가기</Link>
          </p>
        </div>
      </div>
    </main>
  );
}

/* ── 고리 ──────────────────────────────────────────────
   아이를 가운데 두고 네 기관이 둘러선다.
   자리는 눈대중이 아니라 정오각형 계산값이다 — 반지름은 고리와 같은 36, 간격 72°.
   점과 색 호는 이웃한 두 원의 **한가운데**에 하나씩 온다. */

const R = 36;
const CIRC = 2 * Math.PI * R;

/** viewBox(100×100) 안에서 중심으로부터 `deg` 방향, 반지름 R 인 점 */
function at(deg: number) {
  const t = (deg * Math.PI) / 180;
  return { x: 50 + R * Math.cos(t), y: 50 - R * Math.sin(t) };
}

const NODES: [number, string, string][] = [
  [90, "학교", "#5DA7EA"],
  [18, "센터", "#5CC08F"],
  [-54, "활동지원사", "#F7D046"],
  [-126, "부모", "#EF6B4B"],
  [162, "아이", "#C2A6F0"],
];

/** 이웃한 두 원의 한가운데 — [각도, 호 색] */
const MID: [number, string][] = [
  [54, "#9CC6EF"],
  [-18, "#9FD9BE"],
  [-90, "#F6C96A"],
  [-162, "#F5A98E"],
  [126, "#CDB8F1"],
];

/** 색 호 하나의 길이. 둘레를 다섯으로 나눈 값(72°)이라 호끼리 **정확히 맞물린다**.
   경계는 중심 ±36° 에 생기고, 그 자리가 원 다섯 개의 자리와 같다.
   이보다 짧으면 호 사이에 맨 고리선이 드러난다. */
const SEG = CIRC / 5;

/** 호가 `deg` 를 한가운데 두도록 하는 dashoffset. 경로는 3시 방향에서 시계 방향으로 간다. */
function arcOffset(deg: number) {
  const cw = (((-deg % 360) + 360) % 360) / 360;
  return -(cw * CIRC - SEG / 2);
}

function Ring() {
  return (
    <div className="wheel">
      <svg viewBox="0 0 100 100" aria-hidden>
        <circle cx="50" cy="50" r={R} fill="none" stroke="#E3D7C3" strokeWidth=".35" />
        {MID.map(([deg, arc]) => (
          <circle
            key={`arc${deg}`}
            cx="50"
            cy="50"
            r={R}
            fill="none"
            stroke={arc}
            strokeWidth=".35"
            strokeDasharray={`${SEG} ${CIRC - SEG}`}
            strokeDashoffset={arcOffset(deg)}
          />
        ))}
      </svg>

      {/* 세 줄을 한 묶음으로 두고 그 묶음의 가운데를 고리 중심에 맞춘다. */}
      <div className="center">
        <img src="/img/logo.png" alt="잇다" width={600} height={220} />
        <p className="sub">
          아이의 세상을 <b>잇다</b>
        </p>
        <p className="tag">
          <i style={{ background: "#2BAA7E" }} />
          끊김 없는 돌봄, 이어지는 성장
          <i style={{ background: "#EF8C3B" }} />
        </p>
      </div>

      {/* 자리마다 뜻이 있지만(학교·센터·활동지원사·부모·아이) 화면에는 색만 둔다.
          읽히지 않는 색에 뜻이 담기므로 고리 전체에 이름을 한 번 붙인다. */}
      <ul className="orbit" aria-label="학교, 센터, 활동지원사, 부모, 아이를 잇습니다">
        {NODES.map(([deg, label, color]) => {
          const p = at(deg);
          return (
            <li
              key={label}
              className="node"
              style={{ left: `${p.x}%`, top: `${p.y}%`, background: color }}
            >
              <span className="sr">{label}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function KakaoMark() {
  return (
    <svg aria-hidden viewBox="0 0 30 28" width="30" height="28">
      <path
        d="M15 2C7.3 2 1 6.9 1 13c0 3.9 2.6 7.3 6.4 9.3L6 27l5.3-3.5c1.2.2 2.4.3 3.7.3 7.7 0 14-4.9 14-11S22.7 2 15 2z"
        fill="#191919"
      />
    </svg>
  );
}

function Arrow() {
  return (
    <svg
      aria-hidden
      viewBox="0 0 24 24"
      width="22"
      height="22"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.4"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M5 12h14" />
      <path d="M13 6l6 6-6 6" />
    </svg>
  );
}
