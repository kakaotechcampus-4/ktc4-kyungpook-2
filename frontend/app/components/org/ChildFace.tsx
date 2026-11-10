export function ChildFace({ size = 40 }: { size?: number }) {
  return (
    <img
      src="/img/kid-04.png"
      alt=""
      aria-hidden
      width={size}
      height={size}
      style={{ width: size, height: size }}
      className="shrink-0 object-contain"
    />
  );
}
