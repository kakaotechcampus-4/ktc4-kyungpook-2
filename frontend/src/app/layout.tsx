import type { Metadata, Viewport } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "잇다 ITDA",
  description:
    "기관마다 흩어진 장애아동 관찰 기록을 연결하고, 검증된 돌봄 정보를 안전하게 공유하는 통합 돌봄 지원 시스템",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
