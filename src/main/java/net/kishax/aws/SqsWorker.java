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
  private final String queueMode;
  private final ObjectMapper objectMapper;
  private final RedisClient redisClient;
  private final WebToMcMessageSender webToMcSender;
  private final McToWebMessageSender mcToWebSender;
  private final WebApiClient webApiClient;
  private final Configuration configuration;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private RedisClient.RedisSubscription webToMcSubscription;

  // Callback for OTP display integration
  private static OtpDisplayCallback otpDisplayCallback;
  private static AuthConfirmCallback authConfirmCallback;

  /**
   * Interface for OTP display callback
   */
  public interface OtpDisplayCallback {
    void displayOtp(String playerName, String playerUuid, String otp);
  }

  /**
   * Interface for auth confirm callback
   */
  public interface AuthConfirmCallback {
    void onAuthConfirm(String playerName, String playerUuid);
  }

  /**
   * Set the OTP display callback
   */
  public static void setOtpDisplayCallback(OtpDisplayCallback callback) {
    otpDisplayCallback = callback;
  }

  /**
   * Set the Auth Confirm callback
   */
  public static void setAuthConfirmCallback(AuthConfirmCallback callback) {
    authConfirmCallback = callback;
  }

  public SqsWorker(SqsClient sqsClient, String queueUrl, String queueMode, RedisClient redisClient,
      WebToMcMessageSender webToMcSender, McToWebMessageSender mcToWebSender, WebApiClient webApiClient,
      Configuration configuration) {
    this.sqsClient = sqsClient;
    this.queueUrl = queueUrl;
    this.queueMode = queueMode;
    this.redisClient = redisClient;
    this.webToMcSender = webToMcSender;
    this.mcToWebSender = mcToWebSender;
    this.webApiClient = webApiClient;
    this.configuration = configuration;
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

    // Create WebApiClient
    WebApiClient webApiClient = new WebApiClient(config.getWebApiUrl(), config.getWebApiKey());

    return new SqsWorker(sqsClient, pollingQueueUrl, queueMode, redisClient, webToMcSender, mcToWebSender,
        webApiClient, config);
  }

  /**
   * Start SQS message polling
   */
  public void start() {
    if (running.compareAndSet(false, true)) {
      logger.info("🚀 Starting SQS Worker for auth tokens...");
      logger.info("📡 Polling queue: {}", queueUrl);
      logger.info("🔧 Queue mode: {}", queueMode);
      System.out.println("SQS Worker started - Queue URL: " + queueUrl);

      // Start polling with configured interval
      int pollingInterval = configuration.getSqsWorkerPollingInterval();
      executor.scheduleWithFixedDelay(this::pollMessages, 0, pollingInterval, TimeUnit.SECONDS);

      // Subscribe to Redis Pub/Sub for web_to_mc messages if QUEUE_MODE is WEB
      if ("WEB".equalsIgnoreCase(queueMode)) {
        try {
          logger.info("🔔 Subscribing to Redis channel web_to_mc (QUEUE_MODE=WEB)");
          webToMcSubscription = redisClient.subscribe("web_to_mc", this::handleWebToMcMessage);
          logger.info("✅ Successfully subscribed to web_to_mc Redis channel");
        } catch (Exception e) {
          logger.error("❌ Failed to subscribe to web_to_mc Redis channel: {}", e.getMessage(), e);
        }
      } else {
        logger.info("ℹ️ Skipping Redis subscription (QUEUE_MODE={}, only WEB mode subscribes)", queueMode);
      }
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

      // Stop Redis subscription if active
      if (webToMcSubscription != null) {
        try {
          webToMcSubscription.unsubscribe();
          logger.info("🔇 Unsubscribed from web_to_mc Redis channel");
        } catch (Exception e) {
          logger.error("❌ Error unsubscribing from Redis: {}", e.getMessage(), e);
        }
      }

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
          .maxNumberOfMessages(configuration.getSqsWorkerMaxMessages())
          .waitTimeSeconds(configuration.getSqsWorkerWaitTime())
          .visibilityTimeout(configuration.getSqsWorkerVisibilityTimeout())
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

      String receiptHandleSnippet = message.receiptHandle();
      if (receiptHandleSnippet != null && receiptHandleSnippet.length() > 20) {
        receiptHandleSnippet = receiptHandleSnippet.substring(0, 20) + "...";
      }

      logger.info("🔍 Processing message type: {} (ID: {}, Receipt: {})",
          messageType, message.messageId(), receiptHandleSnippet);

      switch (messageType) {
        case "auth_token" -> {
          handleAuthTokenMessage(messageData);
          deleteMessage(message);
          logger.info("✅ Auth token message processed and deleted successfully");
        }
        case "web_mc_otp" -> {
          handleWebMcOtpMessage(messageData);
          deleteMessage(message);
          logger.info("✅ Web MC OTP message processed and deleted successfully");
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
        case "web_mc_auth_confirm" -> {
          handleWebMcAuthConfirmMessage(messageData);
          deleteMessage(message);
          logger.info("✅ Web MC Auth Confirm message processed and deleted successfully");
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
   * Handle auth token message - forward to Redis pub/sub and WEB API
   */
  private void handleAuthTokenMessage(JsonNode data) {
    try {
      String mcid = data.path("mcid").asText();
      String uuid = data.path("uuid").asText();
      String authToken = data.path("authToken").asText();
      long expiresAt = data.path("expiresAt").asLong();
      String action = data.path("action").asText("confirm");

      logger.info("🎮 Processing auth token for player: {} ({})", mcid, uuid);

      // Forward auth token to Web via Redis pub/sub (for real-time notifications)
      AuthTokenData authTokenData = new AuthTokenData(mcid, uuid, authToken, expiresAt);

      // Save to Redis with TTL (for Web to pick up)
      String key = String.format("auth_token:%s_%s", mcid, uuid);
      redisClient.setWithTtl(key, authTokenData, 600); // 10 minutes TTL
      logger.info("📝 Auth token saved to Redis: {}", key);

      // Publish to Redis Pub/Sub for real-time notifications
      String channelName = String.format("auth_token:%s_%s", mcid, uuid);
      redisClient.publish(channelName, authTokenData);
      logger.info("📡 Published auth token notification: {}", channelName);

      // Send auth token to WEB API (primary integration method)
      try {
        if (webApiClient != null) {
          webApiClient.sendAuthToken(mcid, uuid, authToken, expiresAt, action);
          logger.info("✅ Auth token sent to WEB API for player: {}", mcid);
        } else {
          logger.warn("⚠️ WebApiClient is null, cannot send to WEB API");
        }
      } catch (Exception webApiError) {
        logger.error("❌ Failed to send auth token to WEB API (continuing with Redis): {}", webApiError.getMessage());
        // Don't re-throw - Redis integration can still work
      }

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
   * Handle web_mc_otp message (WEB -> MC OTP display)
   */
  private void handleWebMcOtpMessage(JsonNode data) {
    try {
      String playerName = data.path("playerName").asText();
      String playerUuid = data.path("playerUuid").asText();
      String otp = data.path("otp").asText();

      logger.info("🔐 Processing OTP display request for player: {} ({})", playerName, playerUuid);
      logger.info("📝 OTP to display: {}", otp);

      // Use callback to integrate with Velocity/Spigot OTP display
      if (otpDisplayCallback != null) {
        try {
          otpDisplayCallback.displayOtp(playerName, playerUuid, otp);
          logger.info("✅ OTP display callback executed for player: {}", playerName);
        } catch (Exception callbackError) {
          logger.error("❌ OTP display callback failed for player: {} ({})", playerName, playerUuid, callbackError);
          // Don't re-throw, continue with fallback logging
        }
      } else {
        logger.warn("⚠️ No OTP display callback registered, logging OTP instead");
      }

      // Fallback: log the OTP for debugging
      System.out.println("=== OTP DISPLAY REQUEST ===");
      System.out.println("Player: " + playerName + " (" + playerUuid + ")");
      System.out.println("OTP: " + otp);
      System.out.println("===========================");

      logger.info("✅ OTP display request processed for player: {}", playerName);
    } catch (Exception error) {
      logger.error("❌ Error processing OTP display request for player: {} ({})",
          data.path("playerName").asText(), data.path("playerUuid").asText(), error);
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
   * Handle web to MC auth confirm message
   */
  private void handleWebMcAuthConfirmMessage(JsonNode data) {
    try {
      String playerName = data.path("playerName").asText();
      String playerUuid = data.path("playerUuid").asText();

      logger.info("🔐 Processing web to MC auth confirm for player: {} ({})", playerName, playerUuid);

      if ("WEB".equalsIgnoreCase(queueMode)) {
        // We are in the kishax-aws service, forward to MC
        logger.info("➡️ Forwarding auth confirm to MC plugin...");
        if (webToMcSender != null) {
          webToMcSender.sendAuthConfirm(playerName, playerUuid);
          logger.info("📤 Auth confirm sent to MC for player: {}", playerName);
        } else {
          logger.warn("⚠️ WebToMcMessageSender not available - cannot send auth confirm to MC");
        }
      } else { // Assuming "MC" mode
        // We are in the mc-plugin, execute the action via callback
        logger.info("🔔 Executing auth confirm callback for player: {}", playerName);
        if (authConfirmCallback != null) {
          try {
            authConfirmCallback.onAuthConfirm(playerName, playerUuid);
            logger.info("✅ Auth confirm callback executed for player: {}", playerName);
          } catch (Exception callbackError) {
            logger.error("❌ Auth confirm callback failed for player: {} ({})", playerName, playerUuid, callbackError);
          }
        } else {
          logger.warn("⚠️ No auth confirm callback registered in MC mode.");
        }
      }

      logger.info("✅ Web MC auth confirm message processed successfully");
    } catch (Exception error) {
      logger.error("❌ Error processing web MC auth confirm message: {} ({})",
          data.path("playerName").asText(), data.path("playerUuid").asText(), error);
      throw new RuntimeException(error); // Re-throw to prevent message deletion on error
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
   * Send auth completion message to MC
   */
  public void sendAuthCompletionToMc(String playerName, String playerUuid, String message) {
    if (webToMcSender != null) {
      webToMcSender.sendAuthCompletion(playerName, playerUuid, message);
    } else {
      logger.warn("⚠️ WebToMcMessageSender not available - cannot send auth completion");
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
   * Handle message from web_to_mc Redis channel (QUEUE_MODE=WEB only)
   */
  private void handleWebToMcMessage(String messageJson) {
    try {
      logger.info("📨 Received message from web_to_mc Redis channel");
      JsonNode messageData = objectMapper.readTree(messageJson);

      String messageType = messageData.path("type").asText();
      JsonNode data = messageData.path("data");

      logger.info("🔍 Processing Redis message type: {}", messageType);

      switch (messageType) {
        case "web_mc_otp" -> {
          handleOtpMessage(data);
          logger.info("✅ OTP message from Redis processed successfully");
        }
        case "web_mc_auth_confirm" -> {
          handleAuthConfirmMessage(data);
          logger.info("✅ Auth confirm message from Redis processed successfully");
        }
        case "web_mc_command" -> {
          handleCommandMessage(data);
          logger.info("✅ Command message from Redis processed successfully");
        }
        case "web_mc_player_request" -> {
          handlePlayerRequestMessage(data);
          logger.info("✅ Player request message from Redis processed successfully");
        }
        case "web_mc_auth_completion" -> {
          handleAuthCompletionMessage(data);
          logger.info("✅ Auth completion message from Redis processed successfully");
        }
        default -> {
          logger.warn("! Unknown Redis message type: {}", messageType);
        }
      }
    } catch (Exception error) {
      logger.error("❌ Error processing Redis message: {}", error.getMessage(), error);
    }
  }

  /**
   * Handle OTP message from Redis
   */
  private void handleOtpMessage(JsonNode data) {
    String playerName = data.path("playerName").asText();
    String playerUuid = data.path("playerUuid").asText();
    String otp = data.path("otp").asText();

    logger.info("🔐 Processing OTP from Redis for player: {} ({})", playerName, playerUuid);
    sendOtpToMc(playerName, playerUuid, otp);
  }

  /**
   * Handle auth confirm message from Redis
   */
  private void handleAuthConfirmMessage(JsonNode data) {
    String playerName = data.path("playerName").asText();
    String playerUuid = data.path("playerUuid").asText();

    logger.info("🔒 Processing auth confirm from Redis for player: {} ({})", playerName, playerUuid);
    // This handler only runs in WEB mode, so always forward to MC via SQS.
    if (webToMcSender != null) {
      webToMcSender.sendAuthConfirm(playerName, playerUuid);
      logger.info("📤 Auth confirm sent to MC for player: {}", playerName);
    } else {
      logger.warn("⚠️ WebToMcMessageSender not available - cannot send auth confirm to MC");
    }
  }

  /**
   * Handle command message from Redis
   */
  private void handleCommandMessage(JsonNode data) {
    String commandType = data.path("commandType").asText();
    String playerName = data.path("playerName").asText();
    JsonNode commandData = data.path("data");

    logger.info("⚡ Processing command from Redis: {} for player: {}", commandType, playerName);
    sendCommandToMc(commandType, playerName, commandData);
  }

  /**
   * Handle player request message from Redis
   */
  private void handlePlayerRequestMessage(JsonNode data) {
    String requestType = data.path("requestType").asText();
    String playerName = data.path("playerName").asText();
    JsonNode requestData = data.path("data");

    logger.info("📋 Processing player request from Redis: {} for player: {}", requestType, playerName);
    sendPlayerRequestToMc(requestType, playerName, requestData);
  }

  /**
   * Handle auth completion message from Redis
   */
  private void handleAuthCompletionMessage(JsonNode data) {
    String playerName = data.path("playerName").asText();
    String playerUuid = data.path("playerUuid").asText();
    String message = data.path("message").asText();

    logger.info("🎉 Processing auth completion from Redis for player: {} ({})", playerName, playerUuid);
    sendAuthCompletionToMc(playerName, playerUuid, message);
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
      logger.info("🗑️ Message deleted successfully: {} (Receipt: {})",
          message.messageId(), message.receiptHandle().substring(0, 20) + "...");
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
