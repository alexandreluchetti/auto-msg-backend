# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY platform-core/pom.xml platform-core/
COPY channel-whatsapp/pom.xml channel-whatsapp/
COPY llm-anthropic/pom.xml llm-anthropic/
COPY stt-openai/pom.xml stt-openai/
COPY segment-restaurant/pom.xml segment-restaurant/
COPY platform-app/pom.xml platform-app/
RUN mvn -B -q dependency:go-offline -DexcludeReactor=true || true
COPY . .
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 conversa
USER conversa
WORKDIR /app
COPY --from=build /workspace/platform-app/target/conversa.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
