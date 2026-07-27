FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src

FROM build AS test

RUN mvn --batch-mode --no-transfer-progress test

FROM build AS package

RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=package /workspace/target/line-ai-bot-*.jar /app/app.jar

RUN useradd --system --uid 10001 app
USER app

EXPOSE 8000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
