# --- build stage ---
FROM node:22-alpine AS builder
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Vite는 빌드 시점에 값을 번들에 박아넣는다. 바꾸려면 이미지를 다시 빌드해야 한다.
ARG VITE_API_BASE_URL=
ARG VITE_USE_MOCK=true
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_USE_MOCK=$VITE_USE_MOCK

RUN npm run build

# --- runtime stage ---
FROM nginx:1.27-alpine AS runner
COPY --from=builder /app/build/client /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 3000