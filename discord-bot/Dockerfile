# Discord Bot Dockerfile
FROM openjdk:21-jdk-slim

# 作業ディレクトリを設定
WORKDIR /app

# 必要なツールをインストール
RUN apt-get update && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY . .

# Build the Kishax plugins
RUN if [ ! -f "build/libs/discord-bot-1.0.0.jar" ]; then \
      chmod +x ./gradlew && \
      ./gradlew build --no-daemon; \
    else \
      echo "discord-bot plugins already built, skipping build step"; \
    fi

# ログディレクトリを作成
RUN mkdir -p /app/logs

# 非rootユーザーを作成
RUN useradd -r -u 1000 discord-bot && \
    chown -R discord-bot:discord-bot /app

# ユーザーを切り替え
USER discord-bot

# ヘルスチェック
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# アプリケーションを実行
CMD ["java", "-jar", "/app/build/libs/discord-bot-1.0.0.jar"]
