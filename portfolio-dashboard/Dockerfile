# Stage 1: Build
FROM node:20 AS build
WORKDIR /app

# Increase timeout for slow networks
RUN npm config set fetch-retry-maxtimeout 120000
RUN npm config set fetch-retries 5

COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

# Stage 2: Serve
FROM nginx:stable-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
