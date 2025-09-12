package net.kishax.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SQS Worker for processing messages from MC to Web
 * Replacement for the existing sqs-worker.js
 */
public class SqsWorker {
  private static final Logger logger = LoggerFactory.getLogger(SqsWorker.class);

  private final SqsClient sqsClient;
  private final String queueUrl;
  private final ObjectMapper objectMapper;
  private final RedisClient redisClient;
  private final WebToMcMessageSender webToMcSender;
  private final McToWebMessageSender mcToWebSender;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public SqsWorker(SqsClient sqsClient, String queueUrl, RedisClient redisClient,
      WebToMcMessageSender webToMcSender, McToWebMessageSender mcToWebSender) {
    this.sqsClient = sqsClient;
    this.queueUrl = queueUrl;
    this.redisClient = redisClient;
    this.webToMcSender = webToMcSender;
    this.mcToWebSender = mcToWebSender;
    this.objectMapper = new ObjectMapper();
    this.objectMapper.registerModule(new JavaTimeModule());
    this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "SQS-Worker");
      t.setDaemon(true);
      return t;
    });
  }

  /**
   * Create SqsWorker with QUEUE_MODE awareness
   */
  public static SqsWorker createWithQueueMode(Configuration config) {
    String queueMode = config.getQueueMode();
    String pollingQueueUrl = config.getPollingQueueUrl();
    String sendingQueueUrl = config.getSendingQueueUrl();

    logger.info("🔧 Creating SQS Worker with QUEUE_MODE: {}", queueMode);
    logger.info("📥 Polling from queue: {}", pollingQueueUrl);
    logger.info("📤 Sending to queue: {}", sendingQueueUrl);

    SqsClient sqsClient = config.createSqsClient();
    RedisClient redisClient = config.createRedisClient();

    // WebToMcMessageSender should use the sending queue URL (legacy compatibility)
    WebToMcMessageSender webToMcSender = new WebToMcMessageSender(sqsClient, sendingQueueUrl);
    
    // McToWebMessageSender should use the sending queue URL
    String sourceId = "MC".equals(queueMode) ? "mc-server" : "web-app";
    McToWebMessageSender mcToWebSender = new McToWebMessageSender(sqsClient, sendingQueueUrl, sourceId);

    return new SqsWorker(sqsClient, pollingQueueUrl, redisClient, webToMcSender, mcToWebSender);
  }

  /**
   * Start SQS message polling
   */
  public void start() {
    if (running.compareAndSet(false, true)) {
      logger.info("🚀 Starting SQS Worker for auth tokens...");
      logger.info("📡 Polling queue: {}", queueUrl);
      System.out.println("SQS Worker started - Queue URL: " + queueUrl);

      // Start polling with 5 second interval
      executor.scheduleWithFixedDelay(this::pollMessages, 0, 5, TimeUnit.SECONDS);
    } else {
      logger.warn("SQS Worker is already running");
    }
  }

  /**
   * Stop SQS message polling
   */
  public void stop() {
    if (running.compareAndSet(true, false)) {
      logger.info("🛑 Stopping SQS Worker...");
      executor.shutdown();
      try {
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
          executor.shutdownNow();
        }
      } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Poll messages from SQS queue
   */
  private void pollMessages() {
    if (!running.get()) {
      return;
    }

    try {
      System.out.println("SQS Worker: Polling for messages...");
      ReceiveMessageRequest request = ReceiveMessageRequest.builder()
          .queueUrl(queueUrl)
          .maxNumberOfMessages(10)
          .waitTimeSeconds(5) // Reduced wait time for testing
          .visibilityTimeout(30)
          .messageAttributeNames("All") // Receive all message attributes for compatibility
          .build();

      ReceiveMessageResponse response = sqsClient.receiveMessage(request);
      List<Message> messages = response.messages();

      if (!messages.isEmpty()) {
        logger.info("📨 Received {} messages from SQS", messages.size());
        System.out.println("SQS Worker: Received " + messages.size() + " messages");

        for (Message message : messages) {
          processMessage(message);
        }
      } else {
        System.out.println("SQS Worker: No messages received");
      }
    } catch (Exception error) {
      logger.error("❌ Error polling SQS messages: {}", error.getMessage(), error);
      System.out.println("SQS Worker ERROR: " + error.getMessage());
    }
  }

  /**
   * Process individual SQS message
   */
  private void processMessage(Message message) {
    try {
      if (message.body() == null || message.body().isEmpty()) {
        logger.warn("! Received message without body");
        return;
      }

      JsonNode messageData = objectMapper.readTree(message.body());
      String messageType = messageData.path("type").asText();

      // Log message attributes for compatibility verification
      if (message.messageAttributes() != null && !message.messageAttributes().isEmpty()) {
        String source = message.messageAttributes().containsKey("source")
            ? message.messageAttributes().get("source").stringValue()
            : "unknown";
        logger.debug("📋 Message attributes - Type: {}, Source: {}", messageType, source);
      }

      logger.info("🔍 Processing message type: {}", messageType);

      switch (messageType) {
        case "auth_token" -> {
          handleAuthTokenMessage(messageData);
          deleteMessage(message);
          logger.info("✅ Auth token message processed and deleted successfully");
        }
        case "mc_otp_response" -> {
          handleOtpResponseMessage(messageData);
          deleteMessage(message);
          logger.info("✅ OTP Response message processed and deleted successfully");
        }
        case "mc_web_auth_response" -> {
          handleWebAuthResponseMessage(messageData);
          deleteMessage(message);
          logger.info("✅ Web Auth Response message processed and deleted successfully");
        }
        default -> {
          logger.warn("! Unknown message type: {}", messageType);
          // Still delete unknown messages to prevent them from being reprocessed
          deleteMessage(message);
        }
      }
    } catch (Exception error) {
      logger.error("❌ Error processing SQS message: {}", error.getMessage(), error);
      logger.error("📄 Message body: {}", message.body());
    }
  }

  /**
   * Handle auth token message - forward to Redis pub/sub for Web to handle
   */
  private void handleAuthTokenMessage(JsonNode data) {
    try {
      String mcid = data.path("mcid").asText();
      String uuid = data.path("uuid").asText();
      String authToken = data.path("authToken").asText();
      long expiresAt = data.path("expiresAt").asLong();

      logger.info("🎮 Processing auth token for player: {} ({})", mcid, uuid);

      // Forward auth token to Web via Redis pub/sub
      AuthTokenData authTokenData = new AuthTokenData(mcid, uuid, authToken, expiresAt);

      // Save to Redis with TTL (for Web to pick up)
      String key = String.format("auth_token:%s_%s", mcid, uuid);
      redisClient.setWithTtl(key, authTokenData, 600); // 10 minutes TTL
      logger.info("📝 Auth token saved to Redis: {}", key);

      // Publish to Redis Pub/Sub for real-time notifications
      String channelName = String.format("auth_token:%s_%s", mcid, uuid);
      redisClient.publish(channelName, authTokenData);
      logger.info("📡 Published auth token notification: {}", channelName);

      logger.info("✅ Successfully processed auth token for player: {}", mcid);
    } catch (Exception error) {
      logger.error("❌ Error handling auth token message: {}", error.getMessage(), error);
      throw new RuntimeException(error); // Re-throw to prevent message deletion
    }
  }

  /**
   * Handle OTP response message
   */
  private void handleOtpResponseMessage(JsonNode data) {
    try {
      String mcid = data.path("mcid").asText();
      String uuid = data.path("uuid").asText();
      boolean success = data.path("success").asBoolean();
      String message = data.path("message").asText();
      long timestamp = data.path("timestamp").asLong();

      logger.info("🔐 Processing OTP response for player: {} ({}) - Success: {}", mcid, uuid, success);
      logger.info("📝 Response message: {}", message);
      System.out.println("SQS Worker: Processing OTP response for " + mcid + " - " + uuid);

      // Save OTP response to Redis with TTL
      String key = String.format("otp_response:%s_%s", mcid, uuid);
      OtpResponse otpResponse = new OtpResponse(success, message, timestamp, true);

      System.out.println("SQS Worker: Saving to Redis with key: " + key);
      redisClient.setWithTtl(key, otpResponse, 300); // 5 minutes TTL
      logger.info("📝 OTP response saved to Redis: {}", key);
      System.out.println("SQS Worker: Successfully saved to Redis");

      // Publish to Redis Pub/Sub for real-time notifications
      String channelName = String.format("otp_response:%s_%s", mcid, uuid);
      redisClient.publish(channelName, otpResponse);
      logger.info("📡 Published OTP response notification: {}", channelName);

      logger.info("✅ Successfully processed OTP response for player: {} - Status: {}",
          mcid, success ? "Success" : "Failed");
    } catch (Exception error) {
      logger.error("❌ Error handling OTP response message: {}", error.getMessage(), error);
      throw new RuntimeException(error); // Re-throw to prevent message deletion
    }
  }

  /**
   * Handle web auth response message
   */
  private void handleWebAuthResponseMessage(JsonNode data) {
    try {
      String playerName = data.path("playerName").asText();
      String playerUuid = data.path("playerUuid").asText();
      boolean success = data.path("success").asBoolean();
      String message = data.path("message").asText();

      logger.info("🔒 Processing web auth response for player: {} ({})", playerName, playerUuid);
      logger.info("📝 Auth result: {} - {}", success ? "Success" : "Failed", message);

      // This response is mainly for logging/monitoring purposes
      // MC side has already updated DB and granted permissions

      logger.info("✅ Web auth response processed successfully");
    } catch (Exception error) {
      logger.error("❌ Error processing web auth response: {} ({})",
          data.path("playerName").asText(), data.path("playerUuid").asText(), error);
    }
  }

  /**
   * Send auth confirm message to MC
   */
  public void sendAuthConfirmToMc(String playerName, String playerUuid) {
    if (webToMcSender != null) {
      webToMcSender.sendAuthConfirm(playerName, playerUuid);
    } else {
      logger.warn("⚠️ WebToMcMessageSender not available - cannot send auth confirm");
    }
  }

  /**
   * Send OTP to MC
   */
  public void sendOtpToMc(String playerName, String playerUuid, String otp) {
    if (webToMcSender != null) {
      webToMcSender.sendOtp(playerName, playerUuid, otp);
    } else {
      logger.warn("⚠️ WebToMcMessageSender not available - cannot send OTP");
    }
  }

  /**
   * Send command to MC
   */
  public void sendCommandToMc(String commandType, String playerName, Object data) {
    if (webToMcSender != null) {
      webToMcSender.sendCommand(commandType, playerName, data);
    } else {
      logger.warn("⚠️ WebToMcMessageSender not available - cannot send command");
    }
  }

  /**
   * Send player request to MC
   */
  public void sendPlayerRequestToMc(String requestType, String playerName, Object data) {
    if (webToMcSender != null) {
      webToMcSender.sendPlayerRequest(requestType, playerName, data);
    } else {
      logger.warn("⚠️ WebToMcMessageSender not available - cannot send player request");
    }
  }

  /**
   * Get WebToMcMessageSender for external use (legacy compatibility)
   */
  public WebToMcMessageSender getWebToMcSender() {
    return webToMcSender;
  }

  /**
   * Get McToWebMessageSender for external use (recommended)
   */
  public McToWebMessageSender getMcToWebSender() {
    return mcToWebSender;
  }

  /**
   * Delete message from SQS queue
   */
  private void deleteMessage(Message message) {
    try {
      if (message.receiptHandle() == null) {
        logger.warn("! Cannot delete message: no receipt handle");
        return;
      }

      DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
          .queueUrl(queueUrl)
          .receiptHandle(message.receiptHandle())
          .build();

      sqsClient.deleteMessage(deleteRequest);
      logger.debug("🗑️ Message deleted: {}", message.messageId());
    } catch (Exception error) {
      logger.error("❌ Error deleting SQS message: {}", error.getMessage(), error);
    }
  }

  /**
   * Auth Token data class
   */
  public static class AuthTokenData {
    public final String mcid;
    public final String uuid;
    public final String authToken;
    public final long expiresAt;

    @com.fasterxml.jackson.annotation.JsonCreator
    public AuthTokenData(
        @com.fasterxml.jackson.annotation.JsonProperty("mcid") String mcid,
        @com.fasterxml.jackson.annotation.JsonProperty("uuid") String uuid,
        @com.fasterxml.jackson.annotation.JsonProperty("authToken") String authToken,
        @com.fasterxml.jackson.annotation.JsonProperty("expiresAt") long expiresAt) {
      this.mcid = mcid;
      this.uuid = uuid;
      this.authToken = authToken;
      this.expiresAt = expiresAt;
    }
  }

  /**
   * OTP Response data class
   */
  public static class OtpResponse {
    public final boolean success;
    public final String message;
    public final long timestamp;
    public final boolean received;

    @com.fasterxml.jackson.annotation.JsonCreator
    public OtpResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("success") boolean success,
        @com.fasterxml.jackson.annotation.JsonProperty("message") String message,
        @com.fasterxml.jackson.annotation.JsonProperty("timestamp") long timestamp,
        @com.fasterxml.jackson.annotation.JsonProperty("received") boolean received) {
      this.success = success;
      this.message = message;
      this.timestamp = timestamp;
      this.received = received;
    }
  }
}
