# Build the Angular app, serve it with nginx; /api, /v3, /swagger-ui and /actuator are proxied
# to the filing-api container (upstream "filing-api" on port 8080, see nginx.conf).
FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/web/browser /usr/share/nginx/html
EXPOSE 8080
