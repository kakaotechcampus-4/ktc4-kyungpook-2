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

const NODES = [
  { deg: 90, label: "학교", src: "/img/school.png" },
  { deg: 18, label: "센터", src: "/img/center.png" },
  { deg: -54, label: "활동지원사", src: "/img/adult-05.png" },
  { deg: -126, label: "부모", src: "/img/adult-01.png" },
  { deg: 162, label: "아이", src: "/img/kid-04.png" },
];

/** 이웃한 두 원의 한가운데 — [각도, 점 색, 호 색] */
const MID: [number, string, string][] = [
  [54, "#5DA7EA", "#9CC6EF"],
  [-18, "#5CC08F", "#9FD9BE"],
  [-90, "#F7D046", "#F6C96A"],
  [-162, "#EF6B4B", "#F5A98E"],
  [126, "#C2A6F0", "#CDB8F1"],
];

/** 호가 `deg` 를 한가운데 두도록 하는 dashoffset. 경로는 3시 방향에서 시계 방향으로 간다. */
function arcOffset(deg: number) {
  const cw = (((-deg % 360) + 360) % 360) / 360;
  return -(cw * CIRC - 10);
}

function Ring() {
  return (
    <div className="wheel">
      <svg viewBox="0 0 100 100" aria-hidden>
        <circle cx="50" cy="50" r={R} fill="none" stroke="#E3D7C3" strokeWidth=".35" />
        {MID.map(([deg, , arc]) => (
          <circle
            key={`arc${deg}`}
            cx="50"
            cy="50"
            r={R}
            fill="none"
            stroke={arc}
            strokeWidth=".35"
            strokeDasharray={`20 ${CIRC - 20}`}
            strokeDashoffset={arcOffset(deg)}
          />
        ))}
        {MID.map(([deg, dot]) => {
          const p = at(deg);
          return <circle key={`dot${deg}`} cx={p.x} cy={p.y} r="1.6" fill={dot} />;
        })}
      </svg>

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

      <div className="orbit">
        {NODES.map(({ deg, label, src }) => {
          const p = at(deg);
          return (
            <div key={label} className="node" style={{ left: `${p.x}%`, top: `${p.y}%` }}>
              <em>
                <img src={src} alt="" aria-hidden />
              </em>
              <span>{label}</span>
            </div>
          );
        })}
      </div>
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
