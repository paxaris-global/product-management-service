FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml ./
COPY src ./src

RUN mvn -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/product_management_service-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 8088
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
