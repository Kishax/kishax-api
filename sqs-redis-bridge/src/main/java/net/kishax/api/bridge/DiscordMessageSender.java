package net.kishax.api.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;

/**
 * Handles sending messages to Discord via SQS
 * Messages are sent to TO_DISCORD_QUEUE_URL and processed by sqs-redis-bridge
 */
public class DiscordMessageSender {
  private static final Logger logger = LoggerFactory.getLogger(DiscordMessageSender.class);

  private final SqsClient sqsClient;
  private final String discordQueueUrl;
  private final ObjectMapper objectMapper;

  public DiscordMessageSender(SqsClient sqsClient, String discordQueueUrl) {
    this.sqsClient = sqsClient;
    this.discordQueueUrl = discordQueueUrl;
    this.objectMapper = new ObjectMapper();
    this.objectMapper.registerModule(new JavaTimeModule());
  }

  /**
   * Send player event to Discord
   */
  public void sendPlayerEvent(String eventType, String playerName, String playerUuid, String serverName) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", "player_event");
      message.put("eventType", eventType);
      message.put("playerName", playerName);
      message.put("playerUuid", playerUuid);
      message.put("serverName", serverName);
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, "player_event");
      logger.info("✅ Player event sent to Discord: {} for {} on {}", eventType, playerName, serverName);
    } catch (Exception e) {
      logger.error("❌ Failed to send player event to Discord: {} for {}", eventType, playerName, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send server status to Discord
   */
  public void sendServerStatus(String serverName, String status) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", "server_status");
      message.put("serverName", serverName);
      message.put("status", status);
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, "server_status");
      logger.info("✅ Server status sent to Discord: {} - {}", serverName, status);
    } catch (Exception e) {
      logger.error("❌ Failed to send server status to Discord: {}", serverName, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send embed message to Discord
   */
  public void sendEmbedMessage(String content, int color, String channelId) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", "embed");
      message.put("content", content);
      message.put("color", color);
      message.put("channelId", channelId);
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, "embed");
      logger.info("✅ Embed message sent to Discord: {}", content);
    } catch (Exception e) {
      logger.error("❌ Failed to send embed message to Discord", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send broadcast message to Discord
   */
  public void sendBroadcast(String content, boolean isChat) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", "broadcast");
      message.put("content", content);
      message.put("isChat", isChat);
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, "broadcast");
      logger.info("✅ Broadcast sent to Discord: {}", content);
    } catch (Exception e) {
      logger.error("❌ Failed to send broadcast to Discord", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send generic message to Discord
   */
  public void sendGenericMessage(String messageType, Map<String, Object> payload) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", messageType);
      message.setAll((ObjectNode) objectMapper.valueToTree(payload));
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, messageType);
      logger.info("✅ Generic message sent to Discord: {}", messageType);
    } catch (Exception e) {
      logger.error("❌ Failed to send generic message to Discord: {}", messageType, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Internal method to send SQS message with proper attributes
   */
  private void sendMessage(ObjectNode message, String messageType) throws Exception {
    String messageBody = objectMapper.writeValueAsString(message);

    Map<String, MessageAttributeValue> messageAttributes = Map.of(
        "messageType", MessageAttributeValue.builder()
            .dataType("String")
            .stringValue(messageType)
            .build(),
        "source", MessageAttributeValue.builder()
            .dataType("String")
            .stringValue("mc-server")
            .build(),
        "timestamp", MessageAttributeValue.builder()
            .dataType("String")
            .stringValue(String.valueOf(System.currentTimeMillis()))
            .build());

    SendMessageRequest request = SendMessageRequest.builder()
        .queueUrl(discordQueueUrl)
        .messageBody(messageBody)
        .messageAttributes(messageAttributes)
        .build();

    sqsClient.sendMessage(request);
    logger.debug("📤 Message sent to Discord queue: {} -> {}", messageType, discordQueueUrl);
  }
}