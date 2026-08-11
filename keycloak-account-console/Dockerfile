FROM node:20.18-alpine AS ui-build

WORKDIR /workspace

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY tsconfig.json tsconfig.node.json vite.config.ts ./
COPY public ./public
COPY src ./src
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-21-alpine AS theme-package

WORKDIR /workspace

COPY pom.xml ./
COPY maven-resources ./maven-resources
COPY --from=ui-build /workspace/dist ./dist
RUN mvn --batch-mode resources:resources jar:jar

FROM quay.io/keycloak/keycloak:26.0.8

COPY --from=theme-package /workspace/target/chargeops-account-console-1.0.0.jar /opt/keycloak/providers/chargeops-account-console.jar
