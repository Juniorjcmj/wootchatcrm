# ============================================================
# Wootchat CRM API — Spring Boot 3.3 / Java 21
# Multi-stage: build com Maven → runtime enxuto com JRE Alpine
# ============================================================

# --- Stage 1: build do JAR --------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# Cache de dependências: copia só o pom primeiro
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

# Copia o código e empacota
COPY src ./src
RUN mvn -B -ntp -DskipTests clean package \
 && mv target/*.jar target/app.jar

# --- Stage 2: runtime -------------------------------------
FROM eclipse-temurin:21-jre-alpine

# ffmpeg é necessário para transcoding de áudio (ogg/opus ↔ m4a/aac)
# que o WhatsApp/Safari exigem. tini gerencia sinais e reapeia zombies.
RUN apk add --no-cache ffmpeg tini tzdata curl \
 && addgroup -S app && adduser -S -G app app

ENV TZ=America/Sao_Paulo \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

WORKDIR /app

# JAR do stage anterior
COPY --from=build /workspace/target/app.jar /app/app.jar

# Diretório para uploads de mídia (montar volume em produção)
RUN mkdir -p /app/files/uploads && chown -R app:app /app
USER app

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/api/actuator/health || curl -fsS http://localhost:8080/api/v1/health || exit 1

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
