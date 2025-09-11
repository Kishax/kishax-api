package net.kishax.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Instant;
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
  private final DatabaseClient databaseClient;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public SqsWorker(SqsClient sqsClient, String queueUrl, RedisClient redisClient, DatabaseClient databaseClient) {
    this.sqsClient = sqsClient;
    this.queueUrl = queueUrl;
    this.redisClient = redisClient;
    this.databaseClient = databaseClient;
    this.objectMapper = new ObjectMapper();
    this.objectMapper.registerModule(new JavaTimeModule());
    this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "SQS-Worker");
      t.setDaemon(true);
      return t;
    });
  }

  /**
   * Start SQS message polling
   */
  public void start() {
    if (running.compareAndSet(false, true)) {
      logger.info("🚀 Starting SQS Worker for auth tokens...");
      logger.info("📡 Polling queue: {}", queueUrl);

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
      ReceiveMessageRequest request = ReceiveMessageRequest.builder()
          .queueUrl(queueUrl)
          .maxNumberOfMessages(10)
          .waitTimeSeconds(20) // Long polling
          .visibilityTimeout(30)
          .build();

      ReceiveMessageResponse response = sqsClient.receiveMessage(request);
      List<Message> messages = response.messages();

      if (!messages.isEmpty()) {
        logger.info("📨 Received {} messages from SQS", messages.size());

        for (Message message : messages) {
          processMessage(message);
        }
      }
    } catch (Exception error) {
      logger.error("❌ Error polling SQS messages: {}", error.getMessage(), error);
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
   * Handle auth token message
   */
  private void handleAuthTokenMessage(JsonNode data) {
    try {
      String mcid = data.path("mcid").asText();
      String uuid = data.path("uuid").asText();
      String authToken = data.path("authToken").asText();
      long expiresAt = data.path("expiresAt").asLong();

      logger.info("🎮 Processing auth token for player: {} ({})", mcid, uuid);

      // Save to database via DatabaseClient
      databaseClient.upsertMinecraftPlayer(mcid, uuid, authToken, Instant.ofEpochMilli(expiresAt));

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

      // Save OTP response to Redis with TTL
      String key = String.format("otp_response:%s_%s", mcid, uuid);
      OtpResponse otpResponse = new OtpResponse(success, message, timestamp, true);

      redisClient.setWithTtl(key, otpResponse, 300); // 5 minutes TTL
      logger.info("📝 OTP response saved to Redis: {}", key);

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
   * OTP Response data class
   */
  public static class OtpResponse {
    public final boolean success;
    public final String message;
    public final long timestamp;
    public final boolean received;

    public OtpResponse(boolean success, String message, long timestamp, boolean received) {
      this.success = success;
      this.message = message;
      this.timestamp = timestamp;
      this.received = received;
    }
  }
}
