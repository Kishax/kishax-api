package net.kishax.api.bridge;

import net.kishax.api.common.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real AWS integration tests that use actual AWS services.
 * These tests require valid AWS credentials and real SQS queues.
 *
 * To run these tests:
 * 1. Set RUN_REAL_AWS_TESTS=true in your environment
 * 2. Configure real AWS credentials (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
 * 3. Create actual SQS queues and set their URLs in environment variables
 * 4. Ensure Redis is accessible (local or remote)
 *
 * WARNING: These tests will use real AWS resources and may incur costs!
 */
@EnabledIfEnvironmentVariable(named = "RUN_REAL_AWS_TESTS", matches = "true", disabledReason = "Real AWS tests disabled. Set RUN_REAL_AWS_TESTS=true to enable.")
class RealAwsIntegrationTest {

  private SqsClient sqsClient;
  private RedisClient redisClient;
  private SqsWorker sqsWorker;
  private WebToMcMessageSender webToMcSender;
  private Configuration configuration;
  private ObjectMapper objectMapper;

  private String mcWebQueueUrl;
  private String webMcQueueUrl;

  @BeforeEach
  void setUp() throws Exception {
    // Load configuration from environment variables
    configuration = new Configuration();

    // Validate required configuration
    assertNotNull(configuration.getMcToWebQueueUrl(),
        "MC_WEB_SQS_QUEUE_URL must be set for real AWS tests");

    // For Web→MC queue, get from environment directly
    String webMcQueueFromEnv = System.getenv("WEB_MC_SQS_QUEUE_URL");
    assertNotNull(webMcQueueFromEnv, "WEB_MC_SQS_QUEUE_URL must be set for real AWS tests");
    assertNotNull(configuration.getRedisUrl(),
        "REDIS_URL must be set for real AWS tests");

    mcWebQueueUrl = configuration.getMcToWebQueueUrl();
    webMcQueueUrl = System.getenv("WEB_MC_SQS_QUEUE_URL");

    // Create clients using real AWS configuration
    sqsClient = configuration.createSqsClient();
    redisClient = new RedisClient(configuration.getRedisUrl());

    // Setup SqsWorker
    webToMcSender = new WebToMcMessageSender(sqsClient, webMcQueueUrl);
    McToWebMessageSender mcToWebSender = new McToWebMessageSender(sqsClient, mcWebQueueUrl, "real-aws-test");
    WebApiClient webApiClient = new WebApiClient("http://localhost:3000", null);
    sqsWorker = new SqsWorker(sqsClient, mcWebQueueUrl, "MC", redisClient, webToMcSender, mcToWebSender, webApiClient,
        configuration);

    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());

    System.out.println("🔧 Real AWS Integration Test Setup:");
    System.out.println("   MC→Web Queue: " + mcWebQueueUrl);
    System.out.println("   Web→MC Queue: " + webMcQueueUrl);
    System.out.println("   Redis URL: " + configuration.getRedisUrl());
  }

  @AfterEach
  void tearDown() {
    if (sqsWorker != null) {
      sqsWorker.stop();
    }
    if (redisClient != null) {
      redisClient.close();
    }
    if (sqsClient != null) {
      sqsClient.close();
    }
  }

  @Test
  void testRealAwsMcToWebOtpResponseFlow() throws Exception {
    System.out.println("🧪 Testing real AWS MC→Web OTP response flow...");

    // Start the SQS worker
    CompletableFuture.runAsync(() -> sqsWorker.start());

    // Give worker time to start polling
    Thread.sleep(2000);

    // Create test OTP response message with unique identifier
    String testMcid = "realAwsTestPlayer_" + System.currentTimeMillis();
    ObjectNode messagePayload = objectMapper.createObjectNode();
    messagePayload.put("type", "mc_otp_response");
    messagePayload.put("mcid", testMcid);
    messagePayload.put("uuid", "real-aws-test-uuid-" + System.currentTimeMillis());
    messagePayload.put("success", true);
    messagePayload.put("message", "OTP verified successfully");
    messagePayload.put("timestamp", Instant.now().toEpochMilli());

    System.out.println("📤 Sending OTP response message for: " + testMcid);

    // Send message to real MC → Web queue
    sqsClient.sendMessage(SendMessageRequest.builder()
        .queueUrl(mcWebQueueUrl)
        .messageBody(objectMapper.writeValueAsString(messagePayload))
        .build());

    // Wait for message to be processed and stored in Redis
    String redisKey = "otp_response:" + testMcid + "_real-aws-test-uuid-" + System.currentTimeMillis();
    SqsWorker.OtpResponse storedResponse = null;

    System.out.println("⏳ Waiting for message processing...");

    // Poll Redis for the stored response (with longer timeout for real AWS)
    for (int i = 0; i < 20; i++) {
      storedResponse = redisClient.get(redisKey, SqsWorker.OtpResponse.class);
      if (storedResponse != null) {
        break;
      }
      Thread.sleep(1000); // Longer interval for real AWS
    }

    // Verify the response was stored in Redis
    assertNotNull(storedResponse, "OTP response should be stored in Redis");
    assertTrue(storedResponse.success);
    assertTrue(storedResponse.received);

    System.out.println("✅ OTP response successfully processed and stored in Redis");

    // Stop worker
    sqsWorker.stop();
  }

  @Test
  void testRealAwsWebToMcAuthTokenFlow() throws Exception {
    System.out.println("🧪 Testing real AWS Web→MC auth token flow...");

    // Create test auth token message with unique identifier
    String testMcid = "realAwsAuthTestPlayer_" + System.currentTimeMillis();
    ObjectNode messagePayload = objectMapper.createObjectNode();
    messagePayload.put("type", "auth_token");
    messagePayload.put("mcid", testMcid);
    messagePayload.put("uuid", "real-aws-auth-test-uuid-" + System.currentTimeMillis());
    messagePayload.put("token", "real-aws-test-auth-token-" + System.currentTimeMillis());
    messagePayload.put("expires", Instant.now().plusSeconds(3600).toEpochMilli());

    System.out.println("📤 Sending auth token message for: " + testMcid);

    // Send message to real Web → MC queue
    sqsClient.sendMessage(SendMessageRequest.builder()
        .queueUrl(webMcQueueUrl)
        .messageBody(objectMapper.writeValueAsString(messagePayload))
        .build());

    System.out.println("⏳ Polling for message in Web→MC queue...");

    // Verify the message is in the real queue
    List<Message> messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
        .queueUrl(webMcQueueUrl)
        .maxNumberOfMessages(1)
        .waitTimeSeconds(10) // Longer wait for real AWS
        .build()).messages();

    assertFalse(messages.isEmpty(), "Message should be available in real Web → MC queue");

    Message receivedMessage = messages.get(0);
    ObjectNode receivedPayload = (ObjectNode) objectMapper.readTree(receivedMessage.body());

    assertEquals("auth_token", receivedPayload.get("type").asText());
    assertEquals(testMcid, receivedPayload.get("mcid").asText());
    assertTrue(receivedPayload.get("token").asText().startsWith("real-aws-test-auth-token-"));

    System.out.println("✅ Auth token message successfully sent and received");

    // Clean up: delete the message to avoid reprocessing
    sqsClient.deleteMessage(builder -> builder
        .queueUrl(webMcQueueUrl)
        .receiptHandle(receivedMessage.receiptHandle()));
  }

  @Test
  void testRealRedisPubSubCommunication() throws Exception {
    System.out.println("🧪 Testing real Redis pub/sub communication...");

    String testChannel = "real_aws_test_channel_" + System.currentTimeMillis();
    SqsWorker.OtpResponse testMessage = new SqsWorker.OtpResponse(
        true, "Real AWS pub/sub test message", System.currentTimeMillis(), true);

    System.out.println("📡 Setting up pub/sub on channel: " + testChannel);

    // Start waiting for message
    CompletableFuture<SqsWorker.OtpResponse> future = redisClient.waitForMessage(
        testChannel, SqsWorker.OtpResponse.class, Duration.ofSeconds(10));

    // Give subscription time to establish (longer for real Redis)
    Thread.sleep(500);

    System.out.println("📤 Publishing test message...");

    // Publish message
    redisClient.publish(testChannel, testMessage);

    // Wait for result
    SqsWorker.OtpResponse received = future.get(12, TimeUnit.SECONDS);

    assertNotNull(received);
    assertEquals(testMessage.success, received.success);
    assertEquals(testMessage.message, received.message);

    System.out.println("✅ Real Redis pub/sub communication successful");
  }

  @Test
  void testRealRedisDataStorageWithTtl() throws Exception {
    System.out.println("🧪 Testing real Redis data storage with TTL...");

    String testKey = "real_aws_test_storage_key_" + System.currentTimeMillis();
    SqsWorker.OtpResponse testData = new SqsWorker.OtpResponse(
        true, "Real AWS storage test", System.currentTimeMillis(), true);

    System.out.println("💾 Storing data in Redis with key: " + testKey);

    // Store data with short TTL for testing
    redisClient.setWithTtl(testKey, testData, 30); // 30 seconds TTL

    // Retrieve data immediately
    SqsWorker.OtpResponse retrieved = redisClient.get(testKey, SqsWorker.OtpResponse.class);

    assertNotNull(retrieved, "Data should be retrievable immediately after storage");
    assertEquals(testData.success, retrieved.success);
    assertEquals(testData.message, retrieved.message);

    System.out.println("✅ Data successfully stored and retrieved from real Redis");
  }

  @Test
  void testRealAwsEndToEndFlow() throws Exception {
    System.out.println("🧪 Testing real AWS end-to-end flow...");

    // This test simulates the complete flow:
    // 1. MC plugin sends OTP response via SQS
    // 2. Java worker processes it and stores in Redis + publishes to pub/sub
    // 3. Web app (simulated) receives via pub/sub

    String testMcid = "realAwsE2ETestPlayer_" + System.currentTimeMillis();
    String pubSubChannel = "otp_response:" + testMcid + "_real-aws-e2e-test-uuid";

    // Start the SQS worker
    CompletableFuture.runAsync(() -> sqsWorker.start());

    // Set up pub/sub listener (simulating web app)
    CompletableFuture<SqsWorker.OtpResponse> pubSubFuture = redisClient.waitForMessage(
        pubSubChannel, SqsWorker.OtpResponse.class, Duration.ofSeconds(15));

    // Give worker and pub/sub time to start
    Thread.sleep(2000);

    // Create and send OTP response message
    ObjectNode messagePayload = objectMapper.createObjectNode();
    messagePayload.put("type", "mc_otp_response");
    messagePayload.put("mcid", testMcid);
    messagePayload.put("uuid", "real-aws-e2e-test-uuid");
    messagePayload.put("success", true);
    messagePayload.put("message", "End-to-end test OTP response");
    messagePayload.put("timestamp", Instant.now().toEpochMilli());

    System.out.println("📤 Sending end-to-end test message for: " + testMcid);

    sqsClient.sendMessage(SendMessageRequest.builder()
        .queueUrl(mcWebQueueUrl)
        .messageBody(objectMapper.writeValueAsString(messagePayload))
        .build());

    // Wait for pub/sub message (this proves the entire flow worked)
    SqsWorker.OtpResponse pubSubResponse = pubSubFuture.get(20, TimeUnit.SECONDS);

    assertNotNull(pubSubResponse, "Should receive pub/sub message");
    assertTrue(pubSubResponse.success);
    assertTrue(pubSubResponse.received);

    // Also verify it was stored in Redis
    String redisKey = "otp_response:" + testMcid + "_real-aws-e2e-test-uuid";
    SqsWorker.OtpResponse storedResponse = redisClient.get(redisKey, SqsWorker.OtpResponse.class);
    assertNotNull(storedResponse, "Should be stored in Redis");

    System.out.println("✅ End-to-end flow successful: SQS → Java Worker → Redis Storage + Pub/Sub");

    // Stop worker
    sqsWorker.stop();
  }
}
