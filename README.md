# Kishax AWS Integration

AWS SQS and Redis integration library for Kishax Minecraft infrastructure. This library replaces the Node.js sqs-worker with a Java-based solution that enables communication between Minecraft plugins and the web application.

## Architecture

```
MC Plugins (Java) ↔ AWS SQS ↔ Java Worker ↔ Redis Pub/Sub ↔ Next.js Web App (TypeScript)
```

- **MC → Web**: SQS messages processed by Java worker, stored in Redis, published to pub/sub
- **Web → MC**: Messages sent via SQS, polled by MC plugins
- **Real-time communication**: Redis pub/sub between Java worker and TypeScript web app

## Quick Start

### Development Testing (LocalStack)

1. **Configure environment:**
   ```bash
   cp .env.local.example .env.local
   # Edit .env.local if needed (optional for development)
   ```

2. **Run development tests:**
   ```bash
   make test-local
   ```

### Production Testing (Real AWS)

1. **Configure environment:**
   ```bash
   cp .env.prod.example .env.prod
   # Edit .env.prod with your AWS credentials and queue URLs
   ```

2. **Run production tests:**
   ```bash
   make test-prod
   ```

## Configuration

### Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `AWS_ACCESS_KEY_ID` | AWS access key | Yes (prod) |
| `AWS_SECRET_ACCESS_KEY` | AWS secret key | Yes (prod) |
| `AWS_DEFAULT_REGION` | AWS region | Yes (prod) |
| `MC_WEB_SQS_QUEUE_URL` | MC→Web SQS queue URL | Yes |
| `WEB_MC_SQS_QUEUE_URL` | Web→MC SQS queue URL | Yes |
| `REDIS_URL` | Redis connection URL | Yes |

### Message Types

#### MC → Web (via SQS)
- `auth_token`: Authentication token from MC plugin
- `mc_otp_response`: OTP verification response
- `mc_web_auth_response`: Web authentication response

#### Web → MC (via SQS)
- Messages are polled by MC plugins directly

## Usage

### Java Worker Application

```java
Configuration config = new Configuration();
SqsWorker worker = new SqsWorker(config);
worker.start(); // Starts polling SQS queues
```

### Redis Client

```java
RedisClient redis = new RedisClient("redis://localhost:6379");

// Store with TTL
redis.setWithTtl("key", data, 300);

// Retrieve
MyData data = redis.get("key", MyData.class);

// Pub/Sub
redis.publish("channel", message);
CompletableFuture<MyData> future = redis.waitForMessage("channel", MyData.class, Duration.ofSeconds(10));
```

## Testing

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
docker compose --profile test up
```

## Building

### Regular JAR
```bash
mvn clean package
```

### Fat JAR (with dependencies)
```bash
mvn clean package
# Produces: target/kishax-aws-1.0.0-SNAPSHOT-with-dependencies.jar
```

### Running the Application
```bash
java -jar target/kishax-aws-1.0.0-SNAPSHOT-with-dependencies.jar
```

## Docker Services

### Development Stack
- **Redis**: `redis:7-alpine` on port 6379
- **LocalStack**: `localstack/localstack:3.0` on port 4566
  - Provides: SQS, S3, CloudFormation, IAM

### Health Checks
- Redis: `redis-cli ping`
- LocalStack: `curl -f http://localhost:4566/health`

## Security

- All credentials are loaded from environment variables
- No hardcoded secrets in the codebase
- API authentication for web app communication
- AWS IAM roles recommended for production

## Requirements

- Java 21+
- Maven 3.6+
- Docker & Docker Compose (for testing)
- Redis (local or remote)
- AWS SQS queues (real or LocalStack)

## Dependencies

- AWS SDK for Java v2
- Lettuce Redis Client
- Jackson JSON processing
- SLF4J + Logback logging
- JUnit 5 + Mockito + TestContainers

## Publishing to Maven

This package is intended to be published to Maven Central as `net.kishax:kishax-aws`.

```xml
<dependency>
    <groupId>net.kishax</groupId>
    <artifactId>kishax-aws</artifactId>
    <version>1.0.0</version>
</dependency>
```

### ビルド

```bash
mvn clean compile
mvn clean package
mvn clean install
```

### Maven リポジトリへの公開

```bash
# スナップショット版
mvn clean deploy

# リリース版
mvn release:prepare release:perform
```

## ログ

ログは以下の場所に出力されます：

- コンソール出力: 標準出力/エラー出力
- ファイル出力: `logs/kishax-aws.log`（ローテーション付き）

ログレベルは環境変数 `LOG_LEVEL` で制御可能（DEBUG, INFO, WARN, ERROR）。

## トラブルシューティング

### よくある問題

1. **AWS 認証エラー**: ACCESS_KEY と SECRET_KEY が正しく設定されているか確認
2. **Redis 接続エラー**: Redis サーバーが起動しているか、URL が正しいか確認
3. **SQS 権限エラー**: IAM ポリシーで SQS の読み取り/削除権限があるか確認
4. **Web API エラー**: INTERNAL_API_KEY が設定されているか確認

### デバッグ

```bash
# デバッグログを有効化
LOG_LEVEL=DEBUG java -jar kishax-aws.jar

# AWS SDK のデバッグログ
AWS_SDK_LOG_LEVEL=DEBUG java -jar kishax-aws.jar
```

## 貢献

1. このリポジトリをフォーク
2. 機能ブランチを作成 (`git checkout -b feature/amazing-feature`)
3. 変更をコミット (`git commit -m 'Add amazing feature'`)
4. ブランチにプッシュ (`git push origin feature/amazing-feature`)
5. プルリクエストを作成

## ライセンス

このプロジェクトは [ライセンス名] の下でライセンスされています。
