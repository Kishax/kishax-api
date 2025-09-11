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
import software.amazon.awssdk.services.sqs.model.Message;

import java.net.URI;
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

    // Setup test configuration
    Configuration testConfig = new Configuration() {
      @Override
      public String getMcWebSqsQueueUrl() {
        return mcWebQueueUrl;
      }

      @Override
      public String getWebMcSqsQueueUrl() {
        return webMcQueueUrl;
      }

      @Override
      public String getRedisUrl() {
        return redisUrl;
      }

      @Override
      public String getWebApiBaseUrl() {
        return "http://localhost:3000";
      }

      @Override
      public SqsClient createSqsClient() {
        return sqsClient;
      }

      @Override
      public RedisClient createRedisClient() {
        return redisClient;
      }
    };

    // Setup SqsWorker with test configuration
    sqsWorker = new SqsWorker(testConfig);

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
    messagePayload.put("otp", "123456");
    messagePayload.put("timestamp", Instant.now().toEpochMilli());

    // Send message to MC → Web queue
    sqsClient.sendMessage(SendMessageRequest.builder()
        .queueUrl(mcWebQueueUrl)
        .messageBody(objectMapper.writeValueAsString(messagePayload))
        .build());

    // Wait for message to be processed and stored in Redis
    String redisKey = "otp_response:testPlayer";
    SqsWorker.OtpResponse storedResponse = null;
    
    // Poll Redis for the stored response (with timeout)
    for (int i = 0; i < 10; i++) {
      storedResponse = redisClient.get(redisKey, SqsWorker.OtpResponse.class);
      if (storedResponse != null) {
        break;
      }
      Thread.sleep(500);
    }

    // Verify the response was stored in Redis
    assertNotNull(storedResponse, "OTP response should be stored in Redis");
    assertTrue(storedResponse.success);
    assertEquals("testPlayer", storedResponse.mcid);
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
        true, "Test pub/sub message", System.currentTimeMillis(), true, "testMcid");

    // Start waiting for message
    CompletableFuture<SqsWorker.OtpResponse> future = redisClient.waitForMessage(
        testChannel, SqsWorker.OtpResponse.class, Duration.ofSeconds(5));

    // Give subscription time to establish
    Thread.sleep(100);

    // Publish message
    redisClient.publish(testChannel, testMessage);

    // Wait for result
    SqsWorker.OtpResponse received = future.get(6, TimeUnit.SECONDS);

    assertNotNull(received);
    assertEquals(testMessage.success, received.success);
    assertEquals(testMessage.message, received.message);
    assertEquals(testMessage.mcid, received.mcid);
  }

  @Test
  void testRedisDataStorage() throws Exception {
    String testKey = "test_storage_key";
    SqsWorker.OtpResponse testData = new SqsWorker.OtpResponse(
        true, "Test storage", System.currentTimeMillis(), true, "storageMcid");

    // Store data with TTL
    redisClient.setWithTtl(testKey, testData, 60);

    // Retrieve data
    SqsWorker.OtpResponse retrieved = redisClient.get(testKey, SqsWorker.OtpResponse.class);

    assertNotNull(retrieved);
    assertEquals(testData.success, retrieved.success);
    assertEquals(testData.message, retrieved.message);
    assertEquals(testData.mcid, retrieved.mcid);
  }
}