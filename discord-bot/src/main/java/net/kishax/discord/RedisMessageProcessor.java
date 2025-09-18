package net.kishax.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Redisメッセージプロセッサー
 * SQS直接受信からRedis経由受信に変更
 */
public class RedisMessageProcessor {
  private static final Logger logger = LoggerFactory.getLogger(RedisMessageProcessor.class);
  private static final String DISCORD_REQUESTS_CHANNEL = "discord_requests";
  private static final String DISCORD_RESPONSES_CHANNEL = "discord_responses";

  private final JedisPool jedisPool;
  private final SqsMessageProcessor sqsMessageProcessor;
  private final ObjectMapper objectMapper;
  private final ExecutorService executorService;
  private volatile boolean running = false;

  public RedisMessageProcessor(String redisUrl, SqsMessageProcessor sqsMessageProcessor) {
    this.jedisPool = new JedisPool(redisUrl);
    this.sqsMessageProcessor = sqsMessageProcessor;
    this.objectMapper = new ObjectMapper();
    this.executorService = Executors.newFixedThreadPool(2);
  }

  /**
   * Redisメッセージ購読開始
   */
  public void start() {
    if (running) {
      logger.warn("RedisMessageProcessor is already running");
      return;
    }

    running = true;
    logger.info("Redisメッセージプロセッサーを開始します");

    // Subscribe to discord requests
    CompletableFuture.runAsync(() -> {
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.subscribe(new JedisPubSub() {
          @Override
          public void onMessage(String channel, String message) {
            if (DISCORD_REQUESTS_CHANNEL.equals(channel)) {
              handleRedisMessage(message);
            }
          }

          @Override
          public void onSubscribe(String channel, int subscribedChannels) {
            logger.info("Redisチャンネルに購読しました: {}", channel);
          }

          @Override
          public void onUnsubscribe(String channel, int subscribedChannels) {
            logger.info("Redisチャンネルの購読を解除しました: {}", channel);
          }
        }, DISCORD_REQUESTS_CHANNEL);
      } catch (Exception e) {
        logger.error("Redis購読でエラーが発生しました", e);
      }
    }, executorService);
  }

  /**
   * Redisメッセージ処理停止
   */
  public void stop() {
    if (!running) {
      return;
    }

    running = false;
    logger.info("Redisメッセージプロセッサーを停止します");

    if (executorService != null && !executorService.isShutdown()) {
      executorService.shutdown();
    }

    if (jedisPool != null && !jedisPool.isClosed()) {
      jedisPool.close();
    }
  }

  /**
   * Redisから受信したメッセージの処理
   */
  private void handleRedisMessage(String messageJson) {
    try {
      JsonNode requestNode = objectMapper.readTree(messageJson);
      String type = requestNode.path("type").asText();
      String action = requestNode.path("action").asText();
      JsonNode data = requestNode.path("data");

      logger.debug("Redisメッセージを受信しました: {}", action);

      if ("discord_action".equals(type)) {
        // 既存のSqsMessageProcessorに処理を委譲
        String originalMessage = objectMapper.writeValueAsString(data);

        CompletableFuture.runAsync(() -> {
          try {
            sqsMessageProcessor.processMessage(originalMessage);

            // 成功応答を送信
            sendSuccessResponse(action, requestNode.path("source").asText());
          } catch (Exception e) {
            logger.error("Discord処理でエラーが発生しました: {}", action, e);

            // エラー応答を送信
            sendErrorResponse(action, e.getMessage(), requestNode.path("source").asText());
          }
        }, executorService);
      }

    } catch (Exception e) {
      logger.error("Redisメッセージ処理でエラーが発生しました", e);
    }
  }

  /**
   * 成功応答をRedisで送信
   */
  private void sendSuccessResponse(String originalAction, String source) {
    try {
      Map<String, Object> response = new HashMap<>();
      response.put("type", "discord_response");
      response.put("result", "success");
      response.put("action", originalAction);
      response.put("timestamp", System.currentTimeMillis());

      Map<String, Object> responseData = new HashMap<>();
      responseData.put("source", source);
      response.put("data", responseData);

      String responseJson = objectMapper.writeValueAsString(response);

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.publish(DISCORD_RESPONSES_CHANNEL, responseJson);
        logger.debug("Discord成功応答を送信しました: {}", originalAction);
      }
    } catch (Exception e) {
      logger.error("成功応答送信でエラーが発生しました", e);
    }
  }

  /**
   * エラー応答をRedisで送信
   */
  private void sendErrorResponse(String originalAction, String errorMessage, String source) {
    try {
      Map<String, Object> response = new HashMap<>();
      response.put("type", "discord_response");
      response.put("result", "error");
      response.put("action", originalAction);
      response.put("error_message", errorMessage);
      response.put("timestamp", System.currentTimeMillis());

      Map<String, Object> responseData = new HashMap<>();
      responseData.put("source", source);
      response.put("data", responseData);

      String responseJson = objectMapper.writeValueAsString(response);

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.publish(DISCORD_RESPONSES_CHANNEL, responseJson);
        logger.warn("Discordエラー応答を送信しました: {} - {}", originalAction, errorMessage);
      }
    } catch (Exception e) {
      logger.error("エラー応答送信でエラーが発生しました", e);
    }
  }

  /**
   * 手動でのDiscord応答送信（必要な場合）
   */
  public void sendResponse(String action, String result, Map<String, Object> data, String source) {
    try {
      Map<String, Object> response = new HashMap<>();
      response.put("type", "discord_response");
      response.put("result", result);
      response.put("action", action);
      response.put("timestamp", System.currentTimeMillis());

      Map<String, Object> responseData = new HashMap<>(data);
      responseData.put("source", source);
      response.put("data", responseData);

      String responseJson = objectMapper.writeValueAsString(response);

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.publish(DISCORD_RESPONSES_CHANNEL, responseJson);
        logger.debug("Discord応答を送信しました: {} - {}", action, result);
      }
    } catch (Exception e) {
      logger.error("応答送信でエラーが発生しました", e);
    }
  }
}