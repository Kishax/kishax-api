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
      logger.info("Starting discord bot...");

      // 設定読み込み
      config = new Configuration();

      // Discord Bot初期化
      initDiscordBot();

      // SQSメッセージプロセッサー開始
      sqsProcessor = new SqsMessageProcessor(null, jda, config);

      // Redis使用モードかSQS直接使用モードかを判定
      String redisUrl = config.getRedisUrl();
      if (redisUrl != null && !redisUrl.isEmpty()) {
        logger.info("Starting sqs message processer with redis-connection mode...");
        redisProcessor = new RedisMessageProcessor(redisUrl, sqsProcessor);
        redisProcessor.start();
      } else {
        logger.info("Starting sqs message processer with direct-sqs mode...");
        sqsProcessor.start();
      }

      logger.info("Discord Bot is running.");

      // シャットダウンフック
      Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

    } catch (Exception e) {
      logger.error("An error occurred while starting discord bot", e);
      System.exit(1);
    }
  }

  private void initDiscordBot() throws Exception {
    String token = config.getDiscordToken();
    if (token == null || token.isEmpty()) {
      throw new IllegalArgumentException("Discord token is not set in configuration");
    }

    jda = JDABuilder.createDefault(token)
        .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
        .addEventListeners(new DiscordEventListener(config))
        .build();

    jda.awaitReady();

    // スラッシュコマンド登録
    CommandRegistrar.registerCommands(jda, config);

    logger.info("Discord Bot initialized successfully");
  }

  private void shutdown() {
    logger.info("Shutting down Discord Bot...");

    if (redisProcessor != null) {
      redisProcessor.stop();
    }

    if (sqsProcessor != null) {
      sqsProcessor.stop();
    }

    if (jda != null) {
      jda.shutdown();
    }

    logger.info("Discord Bot shut down successfully");
  }
}
