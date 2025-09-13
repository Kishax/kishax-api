package net.kishax.aws;

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
 * Handles sending messages from MC to WEB via SQS
 * Provides type-safe methods for MC→WEB message types
 */
public class McToWebMessageSender {
  private static final Logger logger = LoggerFactory.getLogger(McToWebMessageSender.class);

  private final SqsClient sqsClient;
  private final String targetQueueUrl;
  private final String sourceIdentifier;
  private final ObjectMapper objectMapper;

  public McToWebMessageSender(SqsClient sqsClient, String targetQueueUrl, String sourceIdentifier) {
    this.sqsClient = sqsClient;
    this.targetQueueUrl = targetQueueUrl;
    this.sourceIdentifier = sourceIdentifier; // "mc-server" or "web-app"
    this.objectMapper = new ObjectMapper();
    this.objectMapper.registerModule(new JavaTimeModule());
  }

  /**
   * Send auth token message (MC → WEB)
   */
  public void sendAuthToken(String mcid, String uuid, String authToken, long expiresAt, String action) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", "auth_token");
      message.put("mcid", mcid);
      message.put("uuid", uuid);
      message.put("authToken", authToken);
      message.put("expiresAt", expiresAt);
      message.put("action", action);
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, "auth_token");
      logger.info("✅ Auth token sent: {} ({}) action: {}", mcid, uuid, action);
    } catch (Exception e) {
      logger.error("❌ Failed to send auth token: {} ({})", mcid, uuid, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send OTP response message (MC → WEB)
   */
  public void sendOtpResponse(String mcid, String uuid, boolean success, String message, long timestamp) {
    try {
      ObjectNode msgNode = objectMapper.createObjectNode();
      msgNode.put("type", "mc_otp_response");
      msgNode.put("mcid", mcid);
      msgNode.put("uuid", uuid);
      msgNode.put("success", success);
      msgNode.put("message", message);
      msgNode.put("timestamp", timestamp);

      sendMessage(msgNode, "mc_otp_response");
      logger.info("✅ OTP response sent: {} ({}) - {}", mcid, uuid, success ? "Success" : "Failed");
    } catch (Exception e) {
      logger.error("❌ Failed to send OTP response: {} ({})", mcid, uuid, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send web auth response message (MC → WEB)
   */
  public void sendWebAuthResponse(String playerName, String playerUuid, boolean success, String message) {
    try {
      ObjectNode msgNode = objectMapper.createObjectNode();
      msgNode.put("type", "mc_web_auth_response");
      msgNode.put("playerName", playerName);
      msgNode.put("playerUuid", playerUuid);
      msgNode.put("success", success);
      msgNode.put("message", message);
      msgNode.put("timestamp", System.currentTimeMillis());

      sendMessage(msgNode, "mc_web_auth_response");
      logger.info("✅ Web auth response sent: {} ({}) - {}", playerName, playerUuid, success ? "Success" : "Failed");
    } catch (Exception e) {
      logger.error("❌ Failed to send web auth response: {} ({})", playerName, playerUuid, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send player status message (MC → WEB)
   */
  public void sendPlayerStatus(String playerName, String playerUuid, String status, String serverName) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", "mc_web_player_status");
      message.put("playerName", playerName);
      message.put("playerUuid", playerUuid);
      message.put("status", status);
      message.put("serverName", serverName);
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, "mc_web_player_status");
      logger.info("✅ Player status sent: {} ({}) - {} on {}", playerName, playerUuid, status, serverName);
    } catch (Exception e) {
      logger.error("❌ Failed to send player status: {} ({})", playerName, playerUuid, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send server info message (MC → WEB)
   */
  public void sendServerInfo(String serverName, String status, int playerCount, Map<String, Object> additionalData) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", "mc_web_server_info");
      message.put("serverName", serverName);
      message.put("status", status);
      message.put("playerCount", playerCount);
      message.set("additionalData", objectMapper.valueToTree(additionalData));
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, "mc_web_server_info");
      logger.info("✅ Server info sent: {} - {} ({} players)", serverName, status, playerCount);
    } catch (Exception e) {
      logger.error("❌ Failed to send server info: {}", serverName, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send auth confirm message (WEB → MC)
   */
  public void sendAuthConfirm(String playerName, String playerUuid) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", "web_mc_auth_confirm");
      message.put("playerName", playerName);
      message.put("playerUuid", playerUuid);
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, "web_mc_auth_confirm");
      logger.info("✅ Auth confirm sent: {} ({})", playerName, playerUuid);
    } catch (Exception e) {
      logger.error("❌ Failed to send auth confirm: {} ({})", playerName, playerUuid, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send OTP message (WEB → MC)
   */
  public void sendOtp(String playerName, String playerUuid, String otp) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", "web_mc_otp");
      message.put("playerName", playerName);
      message.put("playerUuid", playerUuid);
      message.put("otp", otp);
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, "web_mc_otp");
      logger.info("✅ OTP sent: {} ({}) OTP: {}", playerName, playerUuid, otp);
    } catch (Exception e) {
      logger.error("❌ Failed to send OTP: {} ({})", playerName, playerUuid, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send command message (WEB → MC)
   */
  public void sendCommand(String commandType, String playerName, Object data) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", "web_mc_command");
      message.put("commandType", commandType);
      message.put("playerName", playerName);
      message.set("data", objectMapper.valueToTree(data));
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, "web_mc_command");
      logger.info("✅ Command sent: {} for {}", commandType, playerName);
    } catch (Exception e) {
      logger.error("❌ Failed to send command: {} for {}", commandType, playerName, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send player request message (WEB → MC)
   */
  public void sendPlayerRequest(String requestType, String playerName, Object data) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", "web_mc_player_request");
      message.put("requestType", requestType);
      message.put("playerName", playerName);
      message.set("data", objectMapper.valueToTree(data));
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, "web_mc_player_request");
      logger.info("✅ Player request sent: {} for {}", requestType, playerName);
    } catch (Exception e) {
      logger.error("❌ Failed to send player request: {} for {}", requestType, playerName, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send account link notification message (WEB → MC)
   */
  public void sendAccountLink(String playerName, String playerUuid, String kishaxUserId) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", "web_mc_account_link");
      message.put("playerName", playerName);
      message.put("playerUuid", playerUuid);
      message.put("kishaxUserId", kishaxUserId);
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, "web_mc_account_link");
      logger.info("✅ Account link sent: {} ({}) linked to {}", playerName, playerUuid, kishaxUserId);
    } catch (Exception e) {
      logger.error("❌ Failed to send account link: {} ({})", playerName, playerUuid, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send generic message with custom payload
   */
  public void sendGenericMessage(String messageType, Map<String, Object> payload) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", messageType);
      message.setAll((ObjectNode) objectMapper.valueToTree(payload));
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, messageType);
      logger.info("✅ Generic message sent: {}", messageType);
    } catch (Exception e) {
      logger.error("❌ Failed to send generic message: {}", messageType, e);
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
            .stringValue(sourceIdentifier)
            .build(),
        "timestamp", MessageAttributeValue.builder()
            .dataType("String")
            .stringValue(String.valueOf(System.currentTimeMillis()))
            .build());

    SendMessageRequest request = SendMessageRequest.builder()
        .queueUrl(targetQueueUrl)
        .messageBody(messageBody)
        .messageAttributes(messageAttributes)
        .build();

    sqsClient.sendMessage(request);
    logger.debug("📤 Message sent to queue: {} -> {}", messageType, targetQueueUrl);
  }
}
