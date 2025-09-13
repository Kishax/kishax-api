package net.kishax.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true", disabledReason = "Integration tests disabled")
class LocalStackIntegrationTest {

  @Container
  static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
      .withServices(LocalStackContainer.Service.SQS);

  @Container
  static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
      .withExposedPorts(6379);

  private SqsClient sqsClient;
  private RedisClient redisClient;
  private SqsWorker sqsWorker;
  private WebToMcMessageSender webToMcSender;
  private ObjectMapper objectMapper;

  private String mcWebQueueUrl;
  private String webMcQueueUrl;

  @BeforeEach
  void setUp() throws Exception {
    // Setup SQS client for LocalStack
    sqsClient = SqsClient.builder()
        .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
        .region(Region.of(localstack.getRegion()))
        .build();

    // Create test queues
    mcWebQueueUrl = sqsClient.createQueue(CreateQueueRequest.builder()
        .queueName("mc-web-queue")
        .build()).queueUrl();

    webMcQueueUrl = sqsClient.createQueue(CreateQueueRequest.builder()
        .queueName("web-mc-queue")
        .build()).queueUrl();

    // Setup Redis client
    String redisUrl = String.format("redis://localhost:%d", redis.getMappedPort(6379));
    redisClient = new RedisClient(redisUrl);

    // Create WebToMcMessageSender
    webToMcSender = new WebToMcMessageSender(sqsClient, webMcQueueUrl);

    // Create McToWebMessageSender
    McToWebMessageSender mcToWebSender = new McToWebMessageSender(sqsClient, mcWebQueueUrl, "test-mc");

    // Create WebApiClient for tests
    WebApiClient webApiClient = new WebApiClient("http://localhost:3000", null);

    // Setup SqsWorker with direct dependencies
    sqsWorker = new SqsWorker(sqsClient, mcWebQueueUrl, "MC", redisClient, webToMcSender, mcToWebSender, webApiClient);

    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
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
  void testMcToWebOtpResponseFlow() throws Exception {
    // Start the SQS worker
    CompletableFuture<Void> workerFuture = CompletableFuture.runAsync(() -> sqsWorker.start());

    // Give worker time to start polling
    Thread.sleep(1000);

    // Create test OTP response message
    ObjectNode messagePayload = objectMapper.createObjectNode();
    messagePayload.put("type", "mc_otp_response");
    messagePayload.put("mcid", "testPlayer");
    messagePayload.put("uuid", "test-uuid-123");
    messagePayload.put("success", true);
    messagePayload.put("message", "OTP verified successfully");
    messagePayload.put("timestamp", Instant.now().toEpochMilli());

    // Send message to MC → Web queue
    String messageBody = objectMapper.writeValueAsString(messagePayload);
    System.out.println("Sending SQS message: " + messageBody);
    System.out.println("Queue URL: " + mcWebQueueUrl);

    sqsClient.sendMessage(SendMessageRequest.builder()
        .queueUrl(mcWebQueueUrl)
        .messageBody(messageBody)
        .build());

    System.out.println("SQS message sent successfully");

    // Wait for message to be processed and stored in Redis
    String redisKey = "otp_response:testPlayer_test-uuid-123";
    SqsWorker.OtpResponse storedResponse = null;

    // Poll Redis for the stored response (with timeout)
    System.out.println("Waiting for Redis key: " + redisKey);
    for (int i = 0; i < 10; i++) {
      storedResponse = redisClient.get(redisKey, SqsWorker.OtpResponse.class);
      if (storedResponse != null) {
        System.out.println("Found stored response after " + (i + 1) + " attempts");
        break;
      }
      System.out.println("Attempt " + (i + 1) + ": Key not found, waiting...");
      Thread.sleep(500);
    }

    // Verify the response was stored in Redis
    assertNotNull(storedResponse, "OTP response should be stored in Redis");
    assertTrue(storedResponse.success);
    assertTrue(storedResponse.received);

    // Stop worker
    sqsWorker.stop();
  }

  @Test
  void testWebToMcAuthTokenFlow() throws Exception {
    // Start the SQS worker
    CompletableFuture<Void> workerFuture = CompletableFuture.runAsync(() -> sqsWorker.start());

    // Give worker time to start polling
    Thread.sleep(1000);

    // Create test auth token message
    ObjectNode messagePayload = objectMapper.createObjectNode();
    messagePayload.put("type", "auth_token");
    messagePayload.put("mcid", "authTestPlayer");
    messagePayload.put("uuid", "auth-test-uuid-456");
    messagePayload.put("token", "test-auth-token-789");
    messagePayload.put("expires", Instant.now().plusSeconds(3600).toEpochMilli());

    // Send message to Web → MC queue (this would typically be sent by Web app)
    sqsClient.sendMessage(SendMessageRequest.builder()
        .queueUrl(webMcQueueUrl)
        .messageBody(objectMapper.writeValueAsString(messagePayload))
        .build());

    // In a real scenario, the MC plugin would poll this queue
    // For testing, let's verify the message is in the queue
    List<Message> messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
        .queueUrl(webMcQueueUrl)
        .maxNumberOfMessages(1)
        .waitTimeSeconds(5)
        .build()).messages();

    assertFalse(messages.isEmpty(), "Message should be available in Web → MC queue");

    Message receivedMessage = messages.get(0);
    ObjectNode receivedPayload = (ObjectNode) objectMapper.readTree(receivedMessage.body());

    assertEquals("auth_token", receivedPayload.get("type").asText());
    assertEquals("authTestPlayer", receivedPayload.get("mcid").asText());
    assertEquals("test-auth-token-789", receivedPayload.get("token").asText());

    // Stop worker
    sqsWorker.stop();
  }

  @Test
  void testRedisPubSubCommunication() throws Exception {
    String testChannel = "test_channel";
    SqsWorker.OtpResponse testMessage = new SqsWorker.OtpResponse(
        true, "Test pub/sub message", System.currentTimeMillis(), true);

    System.out.println("Testing Redis pub/sub with channel: " + testChannel);

    // Start waiting for message
    CompletableFuture<SqsWorker.OtpResponse> future = redisClient.waitForMessage(
        testChannel, SqsWorker.OtpResponse.class, Duration.ofSeconds(5));

    // Give subscription time to establish
    Thread.sleep(100);

    System.out.println("Publishing message to channel: " + testChannel);

    // Publish message
    try {
      redisClient.publish(testChannel, testMessage);
      System.out.println("Message published successfully");
    } catch (Exception e) {
      System.out.println("Failed to publish message: " + e.getMessage());
      throw e;
    }

    // Wait for result
    try {
      SqsWorker.OtpResponse received = future.get(6, TimeUnit.SECONDS);
      System.out.println("Received message: " + (received != null ? "SUCCESS" : "NULL"));

      assertNotNull(received);
      assertEquals(testMessage.success, received.success);
      assertEquals(testMessage.message, received.message);
    } catch (Exception e) {
      System.out.println("Failed to receive message: " + e.getMessage());
      throw e;
    }
  }

  @Test
  void testRedisDataStorage() throws Exception {
    String testKey = "test_storage_key";
    SqsWorker.OtpResponse testData = new SqsWorker.OtpResponse(
        true, "Test storage", System.currentTimeMillis(), true);

    System.out.println("Testing Redis storage with key: " + testKey);

    // Store data with TTL
    try {
      redisClient.setWithTtl(testKey, testData, 60);
      System.out.println("Successfully stored data in Redis");
    } catch (Exception e) {
      System.out.println("Failed to store data in Redis: " + e.getMessage());
      throw e;
    }

    // Retrieve data
    try {
      SqsWorker.OtpResponse retrieved = redisClient.get(testKey, SqsWorker.OtpResponse.class);
      System.out.println("Retrieved data from Redis: " + (retrieved != null ? "SUCCESS" : "NULL"));

      assertNotNull(retrieved);
      assertEquals(testData.success, retrieved.success);
      assertEquals(testData.message, retrieved.message);
    } catch (Exception e) {
      System.out.println("Failed to retrieve data from Redis: " + e.getMessage());
      throw e;
    }
  }

  @Test
  void testBidirectionalCommunication() throws Exception {
    System.out.println("Testing bidirectional communication between Web and MC");

    // Test Web → MC auth confirm
    String testPlayerName = "testPlayer_" + System.currentTimeMillis();
    String testPlayerUuid = "test-uuid-" + System.currentTimeMillis();

    System.out.println("Sending auth confirm to MC via Web→MC queue");
    webToMcSender.sendAuthConfirm(testPlayerName, testPlayerUuid);

    // Verify message is in Web→MC queue
    Thread.sleep(1000);
    List<Message> webToMcMessages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
        .queueUrl(webMcQueueUrl)
        .maxNumberOfMessages(1)
        .waitTimeSeconds(5)
        .build()).messages();

    assertFalse(webToMcMessages.isEmpty(), "Auth confirm message should be in Web→MC queue");

    Message authMessage = webToMcMessages.get(0);
    ObjectNode authPayload = (ObjectNode) objectMapper.readTree(authMessage.body());

    assertEquals("web_mc_auth_confirm", authPayload.get("type").asText());
    assertEquals(testPlayerName, authPayload.get("playerName").asText());
    assertEquals(testPlayerUuid, authPayload.get("playerUuid").asText());

    System.out.println("✅ Web→MC auth confirm message verified");

    // Test Web → MC OTP
    String testOtp = "123456";
    webToMcSender.sendOtp(testPlayerName, testPlayerUuid, testOtp);

    Thread.sleep(500);
    List<Message> otpMessages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
        .queueUrl(webMcQueueUrl)
        .maxNumberOfMessages(10)
        .waitTimeSeconds(5)
        .build()).messages();

    // Find OTP message in the list
    Message otpMessage = null;
    for (Message msg : otpMessages) {
      ObjectNode payload = (ObjectNode) objectMapper.readTree(msg.body());
      if ("web_mc_otp".equals(payload.get("type").asText())) {
        otpMessage = msg;
        break;
      }
    }

    assertNotNull(otpMessage, "OTP message should be in Web→MC queue");
    ObjectNode otpPayload = (ObjectNode) objectMapper.readTree(otpMessage.body());

    assertEquals("web_mc_otp", otpPayload.get("type").asText());
    assertEquals(testOtp, otpPayload.get("otp").asText());

    System.out.println("✅ Web→MC OTP message verified");

    // Clean up messages
    sqsClient.deleteMessage(DeleteMessageRequest.builder()
        .queueUrl(webMcQueueUrl)
        .receiptHandle(authMessage.receiptHandle())
        .build());
    sqsClient.deleteMessage(DeleteMessageRequest.builder()
        .queueUrl(webMcQueueUrl)
        .receiptHandle(otpMessage.receiptHandle())
        .build());

    // Clean up any remaining messages
    for (Message msg : otpMessages) {
      if (!msg.equals(authMessage) && !msg.equals(otpMessage)) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
            .queueUrl(webMcQueueUrl)
            .receiptHandle(msg.receiptHandle())
            .build());
      }
    }
  }
}
