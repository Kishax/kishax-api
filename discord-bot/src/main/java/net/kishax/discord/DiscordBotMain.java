package net.kishax.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kishax.api.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discord Bot メインクラス
 * リフレクションを使わずシンプルなJDA実装
 */
public class DiscordBotMain {
  private static final Logger logger = LoggerFactory.getLogger(DiscordBotMain.class);

  private JDA jda;
  private Configuration config;
  private SqsMessageProcessor sqsProcessor;
  private RedisMessageProcessor redisProcessor;

  public static void main(String[] args) {
    new DiscordBotMain().start();
  }

  public void start() {
    try {
      logger.info("Discord Bot を起動しています...");

      // 設定読み込み
      config = new Configuration();

      // Discord Bot初期化
      initDiscordBot();

      // SQSメッセージプロセッサー開始
      sqsProcessor = new SqsMessageProcessor(null, jda, config);

      // Redis使用モードかSQS直接使用モードかを判定
      String redisUrl = config.getRedisUrl();
      if (redisUrl != null && !redisUrl.isEmpty()) {
        logger.info("Redis経由モードで起動します");
        redisProcessor = new RedisMessageProcessor(redisUrl, sqsProcessor);
        redisProcessor.start();
      } else {
        logger.info("SQS直接モードで起動します");
        sqsProcessor.start();
      }

      logger.info("Discord Bot が正常に起動しました");

      // シャットダウンフック
      Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

    } catch (Exception e) {
      logger.error("Discord Bot の起動に失敗しました", e);
      System.exit(1);
    }
  }

  private void initDiscordBot() throws Exception {
    String token = config.getDiscordToken();
    if (token == null || token.isEmpty()) {
      throw new IllegalArgumentException("Discord Token が設定されていません");
    }

    jda = JDABuilder.createDefault(token)
        .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
        .addEventListeners(new DiscordEventListener(config))
        .build();

    jda.awaitReady();

    // スラッシュコマンド登録
    CommandRegistrar.registerCommands(jda, config);

    logger.info("Discord Bot にログインしました");
  }

  private void shutdown() {
    logger.info("Discord Bot をシャットダウンしています...");

    if (redisProcessor != null) {
      redisProcessor.stop();
    }

    if (sqsProcessor != null) {
      sqsProcessor.stop();
    }

    if (jda != null) {
      jda.shutdown();
    }

    logger.info("Discord Bot がシャットダウンしました");
  }
}
