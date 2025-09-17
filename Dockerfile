FROM eclipse-temurin:21-jdk as builder

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy parent POM and module POMs
COPY pom.xml .
COPY common/pom.xml ./common/
COPY sqs-redis-bridge/pom.xml ./sqs-redis-bridge/
COPY mc-auth/pom.xml ./mc-auth/

# Copy source files
COPY common/src ./common/src
COPY sqs-redis-bridge/src ./sqs-redis-bridge/src
COPY mc-auth/src ./mc-auth/src

# Build all modules
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre

# Install curl for health checks and supervisor for process management
RUN apt-get update && apt-get install -y \
    curl \
    supervisor \
    && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy the built JARs from builder stage (use -with-dependencies versions)
COPY --from=builder /app/sqs-redis-bridge/target/sqs-redis-bridge-*-with-dependencies.jar sqs-redis-bridge.jar
COPY --from=builder /app/mc-auth/target/mc-auth-*-with-dependencies.jar mc-auth.jar

# Create supervisor configuration
RUN mkdir -p /var/log/supervisor

# Create supervisor configuration file
COPY <<EOF /etc/supervisor/conf.d/supervisord.conf
[supervisord]
nodaemon=true
user=root
logfile=/var/log/supervisor/supervisord.log
pidfile=/var/run/supervisord.pid

[program:sqs-redis-bridge]
command=java -jar /app/sqs-redis-bridge.jar
autostart=true
autorestart=true
stderr_logfile=/var/log/supervisor/sqs-redis-bridge.err.log
stdout_logfile=/var/log/supervisor/sqs-redis-bridge.out.log
user=appuser

[program:mc-auth]
command=java -jar /app/mc-auth.jar
autostart=true
autorestart=true
stderr_logfile=/var/log/supervisor/mc-auth.err.log
stdout_logfile=/var/log/supervisor/mc-auth.out.log
user=appuser
EOF

# Create non-root user for security
RUN groupadd -r appuser && useradd -r -g appuser appuser
RUN chown -R appuser:appuser /app

# Expose the authentication API port
EXPOSE 8080

# Health check for both services
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# Start supervisor to manage both services
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
