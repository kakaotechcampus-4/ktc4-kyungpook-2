import sys
from pathlib import Path
from PIL import Image

SIZE = 192
CONTENT = 0.84

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
