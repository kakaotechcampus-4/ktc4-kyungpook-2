import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // 기존 infra/docker/compose.yaml 패턴에 맞춰 컨테이너로 배포한다.
  // standalone 은 .next/standalone 에 실행에 필요한 최소 파일만 모아준다.
  output: "standalone",
  poweredByHeader: false,
};

export default nextConfig;
