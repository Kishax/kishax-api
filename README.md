# Kishax API - Multi-Module Architecture

A comprehensive API server and AWS SQS/Redis integration system for Kishax Minecraft infrastructure. This multi-module Maven project provides authentication services and seamless communication between Minecraft plugins and the web application.

## 🏗️ Architecture

```
MC Plugins (Java) ↔ AWS SQS ↔ SQS-Redis Bridge ↔ Redis Pub/Sub ↔ Next.js Web App (TypeScript)
                                        ↕
                                   MC Auth API
```

### Module Overview

- **`common`**: Shared configuration, utilities, and AWS/Redis client management
- **`sqs-redis-bridge`**: SQS message processing and Redis pub/sub bridge service
- **`mc-auth`**: Minecraft authentication API server with database integration

### Communication Flow

- **MC → Web**: SQS messages processed by bridge, stored in Redis, published to pub/sub
- **Web → MC**: Messages sent via SQS, polled by MC plugins
- **Authentication**: Direct API calls to MC Auth service
- **Real-time communication**: Redis pub/sub between bridge and TypeScript web app

## 🚀 Quick Start

### Development Testing (LocalStack)

1. **Configure environment:**
   ```bash
   cp .env.example .env
   # Edit .env if needed (defaults to local development settings)
   ```

2. **Run development tests:**
   ```bash
   make test-local
   ```

3. **Start development stack:**
   ```bash
   docker compose --profile test up
   ```

### Production Testing (Real AWS)

1. **Configure environment:**
   ```bash
   cp .env.example .env
   # Uncomment and edit production settings in .env
   # Set ENVIRONMENT=production
   ```

2. **Run production tests:**
   ```bash
   make test-prod
   ```

3. **Deploy production services:**
   ```bash
   docker compose --profile production up
   ```

## ⚙️ Configuration

### Unified Environment Configuration

The project uses a single `.env` file for both local and production environments. Copy `.env.example` to `.env` and configure as needed.

**Environment Modes:**
- `ENVIRONMENT=local` (default): Uses LocalStack and local services
- `ENVIRONMENT=production`: Uses real AWS services (uncomment production overrides)

### Environment Variables

#### Core AWS Configuration
| Variable | Description | Required |
|----------|-------------|----------|
| `AWS_REGION` | AWS region | Yes |
| `MC_WEB_SQS_ACCESS_KEY_ID` | AWS access key ID | Yes (prod) |
| `MC_WEB_SQS_SECRET_ACCESS_KEY` | AWS secret key | Yes (prod) |
| `MC_TO_WEB_QUEUE_URL` | MC→Web SQS queue URL | Yes |
| `WEB_TO_MC_QUEUE_URL` | Web→MC SQS queue URL | Yes |

#### Redis Configuration
| Variable | Description | Default |
|----------|-------------|---------|
| `REDIS_URL` | Redis connection URL | `redis://localhost:6379` |
| `REDIS_CONNECTION_TIMEOUT` | Connection timeout (ms) | `5000` |
| `REDIS_COMMAND_TIMEOUT` | Command timeout (ms) | `3000` |

#### SQS Worker Configuration
| Variable | Description | Default |
|----------|-------------|---------|
| `QUEUE_MODE` | Operating mode (MC/WEB) | `MC` |
| `SQS_WORKER_ENABLED` | Enable SQS worker | `true` |
| `SQS_WORKER_POLLING_INTERVAL_SECONDS` | Polling interval | `5` |
| `SQS_WORKER_MAX_MESSAGES` | Max messages per poll | `10` |
| `SQS_WORKER_WAIT_TIME_SECONDS` | Long polling wait time | `20` |

#### Authentication API Configuration
| Variable | Description | Default |
|----------|-------------|---------|
| `AUTH_API_ENABLED` | Enable auth API | `true` |
| `AUTH_API_PORT` | API server port | `8080` |
| `AUTH_API_KEY` | API authentication key | Required |
| `DATABASE_URL` | PostgreSQL database URL | Required |

## 📨 Message Types

### MC → Web (via SQS)
- `auth_token`: Authentication token from MC plugin
- `mc_otp_response`: OTP verification response
- `mc_web_auth_response`: Web authentication response

### Web → MC (via SQS)
- `web_mc_auth_confirm`: Authentication confirmation
- `web_mc_otp`: OTP code for verification

## 💻 Usage

### SQS-Redis Bridge Service

```java
Configuration config = new Configuration();
SqsWorker worker = new SqsWorker(config);
worker.start(); // Starts polling SQS queues and Redis pub/sub
```

### MC Authentication API

```bash
# Health check
curl http://localhost:8080/health

# Authentication endpoint
curl -X POST http://localhost:8080/api/auth/check \
  -H "X-API-Key: your_api_key" \
  -H "Content-Type: application/json" \
  -d '{"playerName": "TestPlayer", "playerUuid": "uuid-here"}'
```

### Redis Client Usage

```java
RedisClient redis = new RedisClient("redis://localhost:6379");

// Store with TTL
redis.setWithTtl("key", data, 300);

// Retrieve
MyData data = redis.get("key", MyData.class);

// Pub/Sub
redis.publish("channel", message);
CompletableFuture<MyData> future = redis.waitForMessage(
    "channel", MyData.class, Duration.ofSeconds(10)
);
```

## 🧪 Testing

### Unit Tests
```bash
mvn test
```

### Integration Tests (LocalStack)
```bash
RUN_INTEGRATION_TESTS=true mvn test -Dtest=LocalStackIntegrationTest
```

### Integration Tests (Real AWS)
```bash
RUN_REAL_AWS_TESTS=true mvn test -Dtest=RealAwsIntegrationTest
```

### Docker Test Environment
```bash
docker compose --profile test up --build
```

## 🔨 Building

### Multi-Module Build
```bash
# Build all modules
mvn clean install

# Build specific module
mvn clean install -pl sqs-redis-bridge -am

# Skip tests
mvn clean install -DskipTests
```

### Docker Images
```bash
# Build all service images
docker compose build

# Build specific service
docker build -f Dockerfile.bridge -t kishax-bridge .
docker build -f Dockerfile.auth -t kishax-auth .
```

### Running Services

#### SQS-Redis Bridge
```bash
java -jar sqs-redis-bridge/target/sqs-redis-bridge-*.jar
```

#### MC Authentication API
```bash
java -jar mc-auth/target/mc-auth-*.jar
```

## 🐳 Docker Services

### Development Stack
- **Redis**: `redis:7-alpine` on port 6379 (mapped to avoid host conflicts)
- **LocalStack**: `localstack/localstack:3.0` on port 4566
  - Provides: SQS, S3, CloudFormation, IAM

### Production Services
- **SQS-Redis Bridge**: Containerized message processing service
- **MC Auth API**: Containerized authentication service

### Health Checks
- Redis: `redis-cli ping`
- LocalStack: `curl -f http://localhost:4566/health`
- Auth API: `curl -f http://localhost:8080/health`

## 🚢 Deployment

### Make Commands
```bash
make test-local    # Local testing with LocalStack
make test-prod     # Production testing with real AWS
make build         # Build all modules
make publish       # Publish to Maven Central
make clean         # Clean build artifacts
```

### Maven Central Publishing
```bash
# Configure GPG signing and credentials in ~/.m2/settings.xml
make publish
```

## 🔒 Security

- All credentials loaded from environment variables
- No hardcoded secrets in codebase
- API key authentication for web app communication
- AWS IAM roles recommended for production
- PostgreSQL connection security with SSL support
- Docker containers run as non-root users

## 📄 License

Licensed under the Apache License 2.0. See [LICENSE](LICENSE) file for details.

Copyright 2025 Kishax
