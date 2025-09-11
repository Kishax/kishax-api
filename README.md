# Kishax AWS Integration

Javaベースの AWS SQS ワーカーと Redis Pub/Sub ライブラリです。既存の Node.js `sqs-worker.js` の代替として設計されています。

## 概要

このライブラリは以下の機能を提供します：

- **SQS Worker**: MC → Web 方向のメッセージ処理
- **Redis Client**: Pub/Sub 通信とデータ保存
- **Database Client**: Web API 経由でのデータベース操作

## アーキテクチャ

```
MC (Java) → SQS → SqsWorker (Java) → Redis Pub/Sub → Next.js (TypeScript)
                         ↓
                   Web API (HTTP) → Database
```

## 主要コンポーネント

### SqsWorker
- SQS からのメッセージをポーリング
- 認証トークン、OTP レスポンス、Web 認証レスポンスを処理
- メッセージ処理後に自動削除

### RedisClient
- Lettuce を使用した Redis 接続管理
- Pub/Sub メッセージングとデータ保存
- TTL 付きデータ保存とリアルタイム通知

### DatabaseClient
- HTTP API 経由でのデータベース操作
- MinecraftPlayer レコードの UPSERT
- OTP 更新と認証確認

### Configuration
- 環境変数とプロパティファイルからの設定読み込み
- AWS、Redis、Web API の設定管理
- 設定値のバリデーション

## 設定

### 環境変数

```bash
# AWS Configuration
AWS_REGION=ap-northeast-1
MC_WEB_SQS_ACCESS_KEY_ID=your-access-key
MC_WEB_SQS_SECRET_ACCESS_KEY=your-secret-key
MC_TO_WEB_QUEUE_URL=https://sqs.region.amazonaws.com/account/queue-name

# Redis Configuration
REDIS_URL=redis://localhost:6379

# Web API Configuration
WEB_API_BASE_URL=http://localhost:3000
INTERNAL_API_KEY=your-internal-api-key

# Worker Configuration
SQS_WORKER_ENABLED=true
LOG_LEVEL=INFO
```

### プロパティファイル

`src/main/resources/application.properties` でデフォルト値を設定可能。

## 使用方法

### スタンドアロン実行

```bash
# ビルド
mvn clean package

# 実行
java -jar target/kishax-aws-1.0.0-SNAPSHOT-with-dependencies.jar
```

### ライブラリとして使用

```java
// 設定の読み込み
Configuration config = new Configuration();
config.validate();

// クライアントの作成
SqsClient sqsClient = config.createSqsClient();
RedisClient redisClient = config.createRedisClient();
DatabaseClient databaseClient = config.createDatabaseClient();

// SQS Worker の開始
SqsWorker worker = new SqsWorker(
    sqsClient, 
    config.getMcToWebQueueUrl(), 
    redisClient, 
    databaseClient
);

worker.start();

// 終了時のクリーンアップ
worker.stop();
redisClient.close();
databaseClient.close();
sqsClient.close();
```

## mc-plugins での使用

### build.gradle への依存関係追加

```gradle
dependencies {
    implementation 'net.kishax:kishax-aws:1.0.0-SNAPSHOT'
    // 他の依存関係...
}
```

### Java コードでの使用例

```java
// SQS Worker の初期化と開始
Configuration config = new Configuration();
SqsWorker worker = new SqsWorker(
    config.createSqsClient(),
    config.getMcToWebQueueUrl(),
    config.createRedisClient(),
    config.createDatabaseClient()
);

worker.start();

// Redis Pub/Sub での通信
RedisClient redis = config.createRedisClient();
redis.publish("channel", messageObject);

// メッセージ待機
CompletableFuture<Message> future = redis.waitForMessage(
    "response_channel", 
    Message.class, 
    Duration.ofSeconds(30)
);
```

## Docker での実行

### ECS 環境での Java + TypeScript 同居

```dockerfile
# Multi-stage build example
FROM openjdk:17-jdk-slim as java-builder
COPY apps/kishax-aws /workspace
WORKDIR /workspace
RUN mvn clean package

FROM node:18-alpine as node-builder
COPY apps/web /workspace
WORKDIR /workspace
RUN npm install && npm run build

FROM node:18-alpine
# Install Java runtime
RUN apk add --no-cache openjdk17-jre

# Copy built artifacts
COPY --from=java-builder /workspace/target/kishax-aws-*.jar /app/
COPY --from=node-builder /workspace/.next /app/.next
COPY --from=node-builder /workspace/node_modules /app/node_modules

# Start both services
CMD ["sh", "-c", "java -jar /app/kishax-aws-*.jar & npm start"]
```

## テスト

```bash
# ユニットテスト
mvn test

# 統合テスト（TestContainers で Redis/LocalStack 使用）
mvn verify

# 特定のテストクラス実行
mvn test -Dtest=SqsWorkerTest
```

## 開発

### 前提条件

- Java 17+
- Maven 3.8+
- Redis (開発・テスト用)
- AWS SQS (本番環境)

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