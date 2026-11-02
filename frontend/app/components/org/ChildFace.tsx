/**
 * 아이 자리를 가리키는 **공통 일러스트**. 디자인 캔버스(05-match · 09-fix · 10-kids)가
 * 이름 옆에 두는 그림이다.
 *
 * ⚠️ 아이마다 다른 그림이 아니다 — 실제 사진도 아니다. 아이를 **구별하는 근거로 쓰면 안 되고**,
 * 구별은 이름과 생년월일이 한다(동명이인). 그래서 aria-hidden 이고 alt 도 없다.
 */
export function ChildFace({ size = 40 }: { size?: number }) {
  return (
    <img
      src="/img/kid-04.png"
      alt=""
      aria-hidden
      width={size}
      height={size}
      style={{ width: size, height: size }}
      className="shrink-0 rounded-full bg-reviewsoft object-cover"
    />
  );
}
