# 클레이 그림 규격

> **그림에 틀을 더하지 않는다.** 이 그림들은 **자기 둥근 네모를 이미 가지고 있다.**

바탕색(`bg-*`)이나 모서리(`rounded-*`)를 씌우면 **네모 안에 네모**가 생긴다.
동그라미(`rounded-full`)로 자르면 그림이 가진 네모의 모서리가 잘려 뭉툭해진다.

```
✗  <img className="size-11 rounded-2xl bg-surface2 object-contain p-0.5" />
✗  <img className="size-10 rounded-full bg-reviewsoft object-cover" />
○  <img className="size-11 object-contain" />
```

그림자가 필요하면 네모에 지는 `shadow-*` 가 아니라 **모양을 따라가는** `drop-shadow-*` 를 쓴다.

## 규격

| | |
| --- | --- |
| 캔버스 | **192 × 192 정사각 · 투명** (쓰는 자리가 최대 96px 이라 2배) |
| 내용 | 긴 변이 캔버스의 **84%** · 가운데 |

정사각이고 여백이 같아서, 어느 자리에 넣어도 서로 크기가 어긋나지 않는다.

## 왜 규격을 적어 두는가

2026-11-06 이전 파일들은 만든 방식이 제각각이었다.

| 파일 | 문제 |
| --- | --- |
| `school.png` | **투명도가 없었다** — 흰색 `#fefefe` 가 구워져 있어 크림 바탕에 흰 네모가 떴다 |
| `center.png` · `school.png` | 160×110 · 160×126 — **정사각이 아니라** 정사각 자리에서 혼자 작아 보였다 |
| `kid-04` vs `adult-01` | 좌우 여백 8 vs 14 — 같은 자리인데 인물 크기가 달랐다 |

## 다시 만드는 법

원본은 `assets-char/`(기획 폴더)에 있고 **전부 투명**이다. 거기서 다시 뽑는다.

```bash
python frontend/tools/normalize-art.py <assets-char 경로> frontend/public/img
```
