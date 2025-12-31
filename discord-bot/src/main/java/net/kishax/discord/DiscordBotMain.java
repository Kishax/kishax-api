package net.kishax.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kishax.api.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

/**
 * Discord Bot メインクラス
 * リフレクションを使わずシンプルなJDA実装
 */
public class DiscordBotMain {
  private static final Logger logger = LoggerFactory.getLogger(DiscordBotMain.class);

  private JDA jda;
  private Configuration config;
  private RedisMessageProcessor redisProcessor;
  private EmojiManager emojiManager;
  private MessageIdManager messageIdManager;
  private JedisPool jedisPool;

  public static void main(String[] args) {
    new DiscordBotMain().start();
  }

  public void start() {
    try {
      logger.info("Starting discord bot...");

      // 設定読み込み
      config = new Configuration();

      // Redis接続初期化
      String redisUrl = config.getRedisUrl();
      if (redisUrl == null || redisUrl.isEmpty()) {
        throw new IllegalArgumentException("Redis URL is required for this discord-bot version");
      }
      jedisPool = new JedisPool(redisUrl);
      logger.info("Redis connection initialized: {}", redisUrl);

      // Discord Bot初期化
      initDiscordBot();

      // 必要なマネージャーを初期化
      emojiManager = new EmojiManager(jda, config);
      messageIdManager = new MessageIdManager();

      // Redis使用モードでのみ動作
      logger.info("Starting sqs message processer with redis-connection mode...");
      redisProcessor = new RedisMessageProcessor(redisUrl, jda, config, emojiManager, messageIdManager);
      redisProcessor.start();

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
        .addEventListeners(new DiscordEventListener(config, jedisPool))
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

    if (jedisPool != null && !jedisPool.isClosed()) {
      jedisPool.close();
      logger.info("JedisPool closed");
    }

    if (jda != null) {
      jda.shutdown();
    }

    logger.info("Discord Bot shut down successfully");
  }
}
