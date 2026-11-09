# AI/notify/template.py
"""
보호자 메일 HTML 템플릿.

## 왜 손으로 쓰나

메일 클라이언트는 2000년대 HTML 만 안다. flex·grid·float 이 안 먹고, `<style>` 블록을 떼어
버리는 곳도 있다 (Gmail 웹은 두고, 일부 아웃룩은 지운다). 그래서 **표로 짜고 스타일을 전부
태그에 인라인으로 박는다.** 템플릿 엔진을 쓰지 않는 것도 같은 이유다 — 의존성 하나가 늘 뿐
결과는 같다.

## 글자는 손대지 않는다

인사이트 본문과 근거 문장은 **승인된 글자 그대로** 넣는다. 틀(인사말·절 제목·버튼)만 여기서
쓴다. 아이 기록이 아니라 틀이라 승인 대상이 아니다 (nodes.py 머리말).

## 이미지는 절대 주소다

메일에는 상대 경로가 없다. 앱이 이미 공개로 주는 `/img/*` 를 그대로 가리킨다
({@code NOTIFY_APP_ORIGIN}). 받는 쪽이 이미지를 막아도 글은 그대로 읽히게 `alt` 를 단다.
"""

import html
import os

#: 메일 안의 링크·이미지가 가리킬 주소. 배포마다 다르다.
APP_ORIGIN = os.environ.get("NOTIFY_APP_ORIGIN", "https://iitda.duckdns.org").rstrip("/")

# ── 색 (parent.css 와 같은 값을 쓴다) ──────────────────────────
INK = "#241f1a"
INK2 = "#5c5248"
MUTED = "#8b8075"
LINE = "#eae3d8"
CREAM = "#fdf8ef"
ACCENT = "#1f9d6b"
ACCENT_SOFT = "#e7f4ec"
WARM = "#fff6e0"

FONT = ("-apple-system, BlinkMacSystemFont, 'Apple SD Gothic Neo', 'Malgun Gothic', "
        "'맑은 고딕', Roboto, sans-serif")


def _esc(text: str) -> str:
    """글자를 HTML 에 넣기 전에 반드시 거친다. 아이 기록에 <, & 가 있을 수 있다."""
    return html.escape(text or "", quote=False)


def _date(value: str) -> str:
    parts = (value or "").split("-")
    if len(parts) != 3:
        return value
    return f"{int(parts[1])}월 {int(parts[2])}일"


def _period(period_from: str, period_to: str) -> str:
    dow = "월화수목금토일"
    try:
        import datetime as dt
        a = dt.date.fromisoformat(period_from)
        b = dt.date.fromisoformat(period_to)
        return (f"{a.year}년 {a.month}월 {a.day}일({dow[a.weekday()]}) ~ "
                f"{b.month}월 {b.day}일({dow[b.weekday()]})")
    except ValueError:
        return f"{period_from} ~ {period_to}"


def _header() -> str:
    """
    로고와 그림. 이미지가 막혀도 alt 로 "잇다" 가 읽히고 글은 그대로 보인다.

    그림은 셋을 가로로 둔다 — 아이 · 학교 · 센터. "여러 기관을 잇는다" 가 메일 맨 위에서
    한 번에 보이게 하려는 것이다.
    """
    kids = "".join(
        f'<img src="{APP_ORIGIN}/img/{n}.png" alt="" width="{w}" '
        f'style="display:inline-block;border:0;width:{w}px;height:auto;vertical-align:bottom;'
        f'margin-left:6px;">'
        for n, w in (("kid-04", 72), ("school", 56), ("center", 56))
    )
    return f"""
<tr><td style="background:{WARM};padding:26px 28px 18px;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr>
    <td align="left" valign="bottom" style="padding-right:8px;">
      <img src="{APP_ORIGIN}/img/logo.png" alt="잇다" width="110"
           style="display:block;border:0;outline:none;width:110px;height:auto;">
      <div style="margin-top:8px;font:400 13px/1.4 {FONT};color:{MUTED};">아이의 세상을 잇다</div>
    </td>
    <td align="right" valign="bottom" style="white-space:nowrap;">{kids}</td>
  </tr></table>
</td></tr>
<tr><td style="height:10px;line-height:10px;font-size:0;
               background:linear-gradient(180deg,{WARM} 0%,#ffffff 100%);">&nbsp;</td></tr>"""


def _greeting(child_name: str) -> str:
    name = _esc(child_name)
    return f"""
<tr><td style="padding:28px 28px 0;">
  <div style="font:800 24px/1.35 {FONT};color:{INK};">{name} 보호자님,</div>
  <div style="margin-top:8px;font:800 22px/1.4 {FONT};color:{INK};">
    <span style="background:{WARM};border-radius:8px;padding:2px 10px;">이번 주 소식</span>
    <span style="font-weight:700;">을 전해드려요</span>
  </div>
  <div style="margin-top:16px;font:400 15px/1.75 {FONT};color:{INK2};">
    안녕하세요. 잇다입니다.<br>
    학교와 센터에서 함께 본 이번 주 이야기를 정리해 보내드려요.
  </div>
</td></tr>"""


def _period_box(period_from: str, period_to: str) -> str:
    return f"""
<tr><td style="padding:20px 28px 0;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
         style="background:{CREAM};border-radius:14px;">
    <tr><td style="padding:16px 18px;">
      <div style="font:800 15px/1.4 {FONT};color:{INK};">&#128197;&nbsp; 기간</div>
      <div style="margin-top:6px;font:400 15px/1.5 {FONT};color:{INK2};">
        {_esc(_period(period_from, period_to))}
      </div>
    </td></tr>
  </table>
</td></tr>"""


def _evidence(entry) -> str:
    """근거 한 줄. 화면의 "이 글의 근거" 와 같은 모양이다 — 상황·대처 꼬리표까지."""
    tail = " · 지난 기록" if getattr(entry, "looked_up", False) else ""
    rows = []
    for sentence in entry.sentences:
        support = sentence.role == "대처"
        chip_bg = ACCENT_SOFT if support else "#f1ece3"
        chip_fg = ACCENT if support else INK2
        rows.append(f"""
      <tr>
        <td valign="top" style="padding:4px 8px 4px 0;white-space:nowrap;">
          <span style="display:inline-block;background:{chip_bg};color:{chip_fg};
                       border-radius:999px;padding:2px 9px;font:700 12px/1.6 {FONT};">
            {_esc(sentence.role)}</span>
        </td>
        <td valign="top" style="padding:4px 0;font:400 14px/1.65 {FONT};color:{INK2};">
          {_esc(sentence.text)}</td>
      </tr>""")
    return f"""
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
         style="margin-top:10px;border:1px solid {LINE};border-radius:12px;">
    <tr><td style="padding:12px 14px;">
      <div style="font:700 14px/1.5 {FONT};color:{INK};">
        {_esc(entry.institution_name)}</div>
      <div style="margin-top:2px;font:400 12px/1.5 {FONT};color:{MUTED};">
        {_esc(_date(entry.date))} 일지{_esc(tail)}</div>
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
             style="margin-top:8px;">{"".join(rows)}</table>
    </td></tr>
  </table>"""


def _insight(insight) -> str:
    head = " · ".join(x for x in (insight.institution_name, insight.period_label) if x)
    evidence = "".join(_evidence(e) for e in insight.evidence if e.sentences)
    evidence_title = f"""
      <div style="margin-top:16px;font:700 13px/1.5 {FONT};color:{ACCENT};">이 글의 근거</div>
      """ if evidence else ""
    return f"""
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
         style="background:{CREAM};border-radius:14px;margin-top:12px;">
    <tr><td style="padding:18px;">
      {f'<div style="font:800 15px/1.4 {FONT};color:{INK};">{_esc(head)}</div>' if head else ""}
      <div style="margin-top:10px;font:700 13px/1.5 {FONT};color:{ACCENT};">기관들이 함께 본 것</div>
      <div style="margin-top:6px;font:400 15px/1.75 {FONT};color:{INK};">{_esc(insight.content)}</div>
      {evidence_title}{evidence}
    </td></tr>
  </table>"""


def _body(insights) -> str:
    return f"""
<tr><td style="padding:20px 28px 0;">
  <div style="font:800 16px/1.4 {FONT};color:{INK};">&#128227;&nbsp; 이번 주 이야기</div>
  {"".join(_insight(i) for i in insights)}
</td></tr>"""


def _footer() -> str:
    return f"""
<tr><td style="padding:24px 28px 8px;">
  <div style="font:400 14px/1.7 {FONT};color:{INK2};">
    이 글은 선생님이 확인하고 보낸 내용이에요.<br>
    근거가 된 일지는 잇다에서 함께 볼 수 있어요.
  </div>
</td></tr>
<tr><td align="center" style="padding:6px 28px 28px;">
  <a href="{APP_ORIGIN}/parent/report"
     style="display:inline-block;background:{ACCENT};color:#ffffff;text-decoration:none;
            border-radius:999px;padding:14px 32px;font:800 16px/1 {FONT};">
    잇다에서 자세히 보기 &rsaquo;</a>
</td></tr>
<tr><td style="padding:0 28px 28px;">
  <div style="border-top:1px solid {LINE};padding-top:14px;
              font:400 12px/1.6 {FONT};color:{MUTED};">
    이 메일은 보호자님이 공유에 동의한 기관의 기록으로 만들어졌어요.<br>
    받고 싶지 않으시면 잇다 설정에서 알림을 끄실 수 있어요.
  </div>
</td></tr>"""


def render(child_name: str, period_from: str, period_to: str, insights) -> str:
    """메일 HTML 한 통. 글자는 전부 승인된 것이고 틀만 여기서 만든다."""
    return f"""<!DOCTYPE html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>잇다 주간 소식</title></head>
<body style="margin:0;padding:0;background:#f4f1ea;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
       style="background:#f4f1ea;">
  <tr><td align="center" style="padding:24px 12px;">
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
           style="max-width:600px;background:#ffffff;border-radius:20px;overflow:hidden;">
      {_header()}{_greeting(child_name)}{_period_box(period_from, period_to)}
      {_body(insights)}{_footer()}
    </table>
  </td></tr>
</table>
</body></html>"""
