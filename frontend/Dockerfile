# --- build stage ---
FROM node:22-alpine AS builder
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Vite는 빌드 시점에 값을 번들에 박아넣는다. 바꾸려면 이미지를 다시 빌드해야 한다.
ARG VITE_API_BASE_URL=
ARG VITE_USE_MOCK=true
# 인증만 따로 실연동하기 위한 플래그 — 데이터(VITE_USE_MOCK)와 분리돼 있다.
ARG VITE_AUTH_MOCK=true
# REST API 키는 인가 요청 URL 에 실려 브라우저에 노출되므로 비밀이 아니다.
# client_secret 은 백엔드만 쓴다 — 여기로 넘기지 말 것.
ARG VITE_KAKAO_CLIENT_ID=
# 카카오 콘솔 · 백엔드 kakao.redirect-uri 와 글자까지 같아야 한다.
ARG VITE_KAKAO_REDIRECT_URI=
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_USE_MOCK=$VITE_USE_MOCK
ENV VITE_AUTH_MOCK=$VITE_AUTH_MOCK
ENV VITE_KAKAO_CLIENT_ID=$VITE_KAKAO_CLIENT_ID
ENV VITE_KAKAO_REDIRECT_URI=$VITE_KAKAO_REDIRECT_URI

RUN npm run build

# --- runtime stage ---
FROM nginx:1.27-alpine AS runner
COPY --from=builder /app/build/client /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 3000