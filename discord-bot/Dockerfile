# Builder stage
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY . .

# Build the Discord Bot
RUN if [ ! -f build/libs/discord-bot-*.jar ]; then \
      echo "JAR not found, building from source..."; \
      chmod +x ./gradlew && \
      ./gradlew build --no-daemon; \
    else \
      echo "discord-bot already built, skipping build step"; \
    fi

# Runtime stage - Alpine JRE for minimal size
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Install curl for health checks
RUN apk add --no-cache curl

# Create logs directory
RUN mkdir -p /app/logs

# Create non-root user
RUN addgroup -S discord-bot && \
    adduser -S -G discord-bot discord-bot && \
    chown -R discord-bot:discord-bot /app

# Copy only the JAR from builder
COPY --from=builder --chown=discord-bot:discord-bot /app/build/libs/discord-bot-1.0.0.jar /app/discord-bot.jar

USER discord-bot

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

CMD ["java", "-jar", "/app/discord-bot.jar"]
