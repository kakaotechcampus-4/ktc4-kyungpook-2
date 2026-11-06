"""
클레이 그림을 **같은 규격**으로 다시 만든다.

지금 frontend/public/img 의 파일들은 만든 방식이 제각각이다.
  school.png   투명도가 없다 (흰색 #fefefe 가 구워져 있다)  → 크림 바탕에 흰 네모가 뜬다
  center.png   160x110 · school 160x126                     → 정사각 타일에서 혼자 작아 보인다
  kid-04       좌우 여백 8 · adult-01 좌우 여백 14           → 같은 타일인데 인물 크기가 다르다

규격 한 줄: **정사각 · 투명 · 내용의 긴 변이 캔버스의 84%**
그러면 네모 타일을 써도, 안 써도, 동그라미로 잘라도 서로 어긋나지 않는다.
"""
import sys
from pathlib import Path
from PIL import Image

SIZE = 192          # 96px 로 그리는 자리의 2배 (레티나)
CONTENT = 0.84      # 내용의 긴 변이 캔버스에서 차지하는 비율

def build(src: Path, dst: Path) -> str:
    im = Image.open(src).convert("RGBA")
    box = im.getchannel("A").getbbox()
    if box is None:
        raise SystemExit(f"{src}: 내용이 없다 (전부 투명)")
    art = im.crop(box)
    w, h = art.size
    scale = (SIZE * CONTENT) / max(w, h)
    art = art.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)

    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    canvas.paste(art, ((SIZE - art.width) // 2, (SIZE - art.height) // 2), art)
    dst.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(dst, optimize=True)
    return f"{dst.name:<14} {box} {w}x{h} → {art.width}x{art.height} in {SIZE}x{SIZE}  {dst.stat().st_size//1024}KB"

if __name__ == "__main__":
    src_root, dst_root = Path(sys.argv[1]), Path(sys.argv[2])
    pairs = [
        ("char/school.png", "school.png"),
        ("center.png", "center.png"),
        ("char/kid-04.png", "kid-04.png"),
        ("char/adult-01.png", "adult-01.png"),
        ("char/adult-05.png", "adult-05.png"),
    ]
    for s, d in pairs:
        print(build(src_root / s, dst_root / d))
