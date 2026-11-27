FROM node:24-alpine AS build
ARG VITE_PROMETHEUS_URL=http://localhost:9095
ARG VITE_GRAFANA_URL=http://localhost:3000/d/capyiot
ARG VITE_JAEGER_URL=http://localhost:16686
ENV VITE_PROMETHEUS_URL=$VITE_PROMETHEUS_URL VITE_GRAFANA_URL=$VITE_GRAFANA_URL VITE_JAEGER_URL=$VITE_JAEGER_URL
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run typecheck && npm run build

FROM nginxinc/nginx-unprivileged:1.28-alpine
ARG NGINX_TEMPLATE=infra/nginx.microservices.conf
COPY ${NGINX_TEMPLATE} /etc/nginx/templates/default.conf.template
COPY --chmod=755 infra/19-session-secret.envsh /docker-entrypoint.d/19-session-secret.envsh
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
