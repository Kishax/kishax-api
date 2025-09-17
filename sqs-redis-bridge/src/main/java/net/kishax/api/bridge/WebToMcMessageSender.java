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
 * Handles sending messages from Web to MC plugins via SQS
 */
public class WebToMcMessageSender {
  private static final Logger logger = LoggerFactory.getLogger(WebToMcMessageSender.class);

  private final SqsClient sqsClient;
  private final String webToMcQueueUrl;
  private final ObjectMapper objectMapper;

  public WebToMcMessageSender(SqsClient sqsClient, String webToMcQueueUrl) {
    this.sqsClient = sqsClient;
    this.webToMcQueueUrl = webToMcQueueUrl;
    this.objectMapper = new ObjectMapper();
    this.objectMapper.registerModule(new JavaTimeModule());
  }

  /**
   * Send auth confirm message to MC
   */
  public void sendAuthConfirm(String playerName, String playerUuid) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", "web_mc_auth_confirm");
      message.put("playerName", playerName);
      message.put("playerUuid", playerUuid);
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, "web_mc_auth_confirm");
      logger.info("✅ Auth confirm sent to MC: {} ({})", playerName, playerUuid);
    } catch (Exception e) {
      logger.error("❌ Failed to send auth confirm to MC: {} ({})", playerName, playerUuid, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send OTP to MC
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
      logger.info("✅ OTP sent to MC: {} ({}) OTP: {}", playerName, playerUuid, otp);
    } catch (Exception e) {
      logger.error("❌ Failed to send OTP to MC: {} ({})", playerName, playerUuid, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send command to MC
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
      logger.info("✅ Command sent to MC: {} for {}", commandType, playerName);
    } catch (Exception e) {
      logger.error("❌ Failed to send command to MC: {} for {}", commandType, playerName, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send player request to MC
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
      logger.info("✅ Player request sent to MC: {} for {}", requestType, playerName);
    } catch (Exception e) {
      logger.error("❌ Failed to send player request to MC: {} for {}", requestType, playerName, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send authentication completion message to MC
   */
  public void sendAuthCompletion(String playerName, String playerUuid, String message) {
    try {
      ObjectNode messageObj = objectMapper.createObjectNode();
      messageObj.put("type", "web_mc_auth_completion");
      messageObj.put("playerName", playerName);
      messageObj.put("playerUuid", playerUuid);
      messageObj.put("message", message);
      messageObj.put("timestamp", System.currentTimeMillis());

      sendMessage(messageObj, "web_mc_auth_completion");
      logger.info("✅ Auth completion sent to MC: {} ({})", playerName, playerUuid);
    } catch (Exception e) {
      logger.error("❌ Failed to send auth completion to MC: {} ({})", playerName, playerUuid, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Send generic message to MC
   */
  public void sendGenericMessage(String messageType, Object payload) {
    try {
      ObjectNode message = objectMapper.createObjectNode();
      message.put("type", messageType);
      message.setAll((ObjectNode) objectMapper.valueToTree(payload));
      message.put("timestamp", System.currentTimeMillis());

      sendMessage(message, messageType);
      logger.info("✅ Generic message sent to MC: {}", messageType);
    } catch (Exception e) {
      logger.error("❌ Failed to send generic message to MC: {}", messageType, e);
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
            .stringValue("web-app")
            .build(),
        "timestamp", MessageAttributeValue.builder()
            .dataType("String")
            .stringValue(String.valueOf(System.currentTimeMillis()))
            .build());

    SendMessageRequest request = SendMessageRequest.builder()
        .queueUrl(webToMcQueueUrl)
        .messageBody(messageBody)
        .messageAttributes(messageAttributes)
        .build();

    sqsClient.sendMessage(request);
    logger.debug("📤 Message sent to Web→MC queue: {}", messageType);
  }
}
