FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml ./
COPY src ./src

RUN mvn -Dmaven.test.skip=true package

# Playwright Java image includes Chromium for banner screenshots of provisioned frontends
FROM mcr.microsoft.com/playwright/java:v1.49.0-noble
WORKDIR /app

COPY --from=build /app/target/product_management_service-0.0.1-SNAPSHOT.jar /app/app.jar

ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
ENV SHOWCASE_PLAYWRIGHT_ENABLED=true

EXPOSE 8088
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
