package net.kishax.api.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Discord向けメッセージハンドラー
 * SQSメッセージをRedis経由でdiscord-botに送信
 */
public class DiscordMessageHandler {
  private static final Logger logger = LoggerFactory.getLogger(DiscordMessageHandler.class);
  private static final String DISCORD_REQUESTS_CHANNEL = "discord_requests";

  private final RedisClient redisClient;
  private final ObjectMapper objectMapper;

  public DiscordMessageHandler(RedisClient redisClient) {
    this.redisClient = redisClient;
    this.objectMapper = new ObjectMapper();
  }

  /**
   * SQSメッセージをRedis経由でdiscord-botに送信
   */
  public void handleDiscordMessage(String messageBody) {
    try {
      JsonNode messageJson = objectMapper.readTree(messageBody);
      String messageType = messageJson.path("type").asText();

      // discord-bot向けのメッセージかチェック
      if (isDiscordMessage(messageType)) {
        Map<String, Object> discordRequest = new HashMap<>();
        discordRequest.put("type", "discord_action");
        discordRequest.put("action", messageType);
        discordRequest.put("data", messageJson);
        discordRequest.put("timestamp", System.currentTimeMillis());
        discordRequest.put("source", messageJson.path("source").asText("unknown"));

        String requestJson = objectMapper.writeValueAsString(discordRequest);

        // Redisで公開
        redisClient.publish(DISCORD_REQUESTS_CHANNEL, requestJson);

        logger.info("Sent message to redis for discord-bot: {}", messageType);
      }
    } catch (Exception e) {
      logger.error("An error occurred while processing of discord message", e);
    }
  }

  /**
   * discord-bot向けメッセージ判定
   */
  private boolean isDiscordMessage(String messageType) {
    return switch (messageType) {
      case "server_status",
          "player_request",
          "broadcast",
          "embed",
          "player_event",
          "webhook",
          "bot_message",
          "send_message",
          "edit_message" ->
        true;
      default -> false;
    };
  }

  /**
   * プレイヤーイベントメッセージを生成
   */
  public void sendPlayerEvent(String eventType, String playerName, String playerUuid, String serverName) {
    try {
      Map<String, Object> playerEvent = new HashMap<>();
      playerEvent.put("type", "player_event");
      playerEvent.put("eventType", eventType);
      playerEvent.put("playerName", playerName);
      playerEvent.put("playerUuid", playerUuid);
      playerEvent.put("serverName", serverName);
      playerEvent.put("timestamp", System.currentTimeMillis());

      handleDiscordMessage(objectMapper.writeValueAsString(playerEvent));
    } catch (Exception e) {
      logger.error("An error occurred while sending player event", e);
    }
  }

  /**
   * サーバーステータスメッセージを生成
   */
  public void sendServerStatus(String serverName, String status) {
    try {
      Map<String, Object> serverStatus = new HashMap<>();
      serverStatus.put("type", "server_status");
      serverStatus.put("serverName", serverName);
      serverStatus.put("status", status);
      serverStatus.put("timestamp", System.currentTimeMillis());

      handleDiscordMessage(objectMapper.writeValueAsString(serverStatus));
    } catch (Exception e) {
      logger.error("An error occurred while sending server status", e);
    }
  }

  /**
   * Embedメッセージを生成
   */
  public void sendEmbedMessage(String content, int color, String channelId) {
    try {
      Map<String, Object> embedMessage = new HashMap<>();
      embedMessage.put("type", "embed");
      embedMessage.put("content", content);
      embedMessage.put("color", color);
      embedMessage.put("channelId", channelId);
      embedMessage.put("timestamp", System.currentTimeMillis());

      handleDiscordMessage(objectMapper.writeValueAsString(embedMessage));
    } catch (Exception e) {
      logger.error("An error occurred while sending embed message", e);
    }
  }

  /**
   * ブロードキャストメッセージを生成
   */
  public void sendBroadcast(String content, boolean isChat) {
    try {
      Map<String, Object> broadcast = new HashMap<>();
      broadcast.put("type", "broadcast");
      broadcast.put("content", content);
      broadcast.put("isChat", isChat);
      broadcast.put("timestamp", System.currentTimeMillis());

      handleDiscordMessage(objectMapper.writeValueAsString(broadcast));
    } catch (Exception e) {
      logger.error("An error occurred while sending broadcast message", e);
    }
  }
}
