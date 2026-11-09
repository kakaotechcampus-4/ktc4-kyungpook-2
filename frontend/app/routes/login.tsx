import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router";
import { grantRole, hasReturnPath, isAuthMock, startKakaoLogin } from "@/lib/auth";
import "./login.css";

export default function LoginPage() {
  const navigate = useNavigate();
  const mock = isAuthMock();
  const [expired, setExpired] = useState(false);
  useEffect(() => setExpired(hasReturnPath()), []);

  return (
    <main className="land">
      <div className="blobs" aria-hidden>
        <i style={{ left: -140, top: 120, width: 380, height: 380, background: "#CFEFDF", opacity: 0.7 }} />
        <i style={{ right: -120, top: -60, width: 360, height: 360, background: "#FFE9A8", opacity: 0.7 }} />
        <i style={{ left: -80, bottom: -120, width: 420, height: 420, background: "#E6DAFA", opacity: 0.6 }} />
        <i style={{ right: -60, bottom: 40, width: 380, height: 380, background: "#FFDCCF", opacity: 0.6 }} />
      </div>

      <header className="brandbar">
        <img src="/img/logo.png" alt="잇다" width={120} height={44} />
      </header>

      <div className="inner">
        <Ring />

        {expired ? (
          <p className="expired" role="status">
            로그인이 만료됐어요. 다시 로그인하면 보던 화면으로 돌아가요
          </p>
        ) : null}

        <button type="button" className="kakao" onClick={() => startKakaoLogin("org")}>
          <KakaoMark />
          카카오 로그인하기
          <Arrow />
        </button>

        <p className="lede">아이와 기관의 정보를 연결해 더 나은 돌봄과 더 넓은 가능성을 만듭니다.</p>

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

const R = 36;
const CIRC = 2 * Math.PI * R;

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

const MID: [number, string][] = [
  [54, "#9CC6EF"],
  [-18, "#9FD9BE"],
  [-90, "#F6C96A"],
  [-162, "#F5A98E"],
  [126, "#CDB8F1"],
];

const SEG = CIRC / 5;

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
