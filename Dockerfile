# Build the static SPA, then serve it from nginx (which also reverse-proxies the
# HA API so the browser is same-origin). See deploy/nginx.conf + deploy/README.md.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
RUN rm /etc/nginx/conf.d/default.conf
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
# Run by the render-direct-streams initContainer (deploy/k8s/deployment.yaml), not by nginx.
COPY deploy/render-direct-streams.sh /usr/local/bin/render-direct-streams.sh
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
