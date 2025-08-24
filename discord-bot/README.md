# Kishax Discord Bot (AWS版)

## Note
このレポジトリは、[Kishax/infrastructure](https://github.com/Kishax/infrastructure) にて使われています。

## Architecture

```
App → API Gateway → Lambda → SQS → ECS(Discord Bot)
```

## Components

- **API Gateway**: 外部からのリクエストを受付
- **Lambda**: リクエストを処理してSQSにメッセージを送信
- **SQS**: 非同期処理キュー（メッセージの平準化）
- **ECS Fargate**: Discord Bot常駐（WebSocket接続維持）

## Feature

- ✅ **AWS Native**: ECS + SQS + API Gateway
- ✅ **スケーラブル**: 各サービスが独立してスケール可能
- ✅ **安定接続**: ECSで安定したWebSocket接続維持
- ✅ **コスト最適**: Lambdaは瞬間処理で課金最小

## Tree

```
discord-bot/
├── src/main/java/net/kishax/discord/
│   ├── DiscordBotMain.java          # メインクラス
│   ├── Config.java                  # 設定管理
│   ├── DiscordEventListener.java    # イベント処理
│   ├── CommandRegistrar.java        # コマンド登録
│   └── SqsMessageProcessor.java     # SQS処理
├── src/main/resources/
│   ├── application.conf             # 設定ファイル
│   └── logback.xml                  # ログ設定
├── aws/
│   ├── cloudformation-template.yaml # インフラ定義
│   ├── task-definition.json         # ECSタスク定義
│   ├── service-definition.json      # ECSサービス定義
│   ├── lambda/                      # Lambda関数
│   └── sqs-config.json             # SQS設定
├── Dockerfile                       # Dockerイメージ定義
└── build.gradle                     # ビルド設定
```
## QuickStart

```bash
cp .env.example .env

# ビルド
./gradlew build

# 実行
java -jar build/libs/discord-bot-1.0.0.jar

# イメージビルド
docker build -t kishax-discord-bot .

# コンテナ実行
docker run -e DISCORD_TOKEN="..." -e SQS_QUEUE_URL="..." kishax-discord-bot

# テスト実行
./gradlew test

# アプリケーション実行
./gradlew run
```
