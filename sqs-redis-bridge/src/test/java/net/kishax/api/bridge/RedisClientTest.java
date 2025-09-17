package net.kishax.api.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RedisClientTest {

  private RedisClient redisClient;
  private static final String TEST_REDIS_URL = "redis://localhost:6379";

  @BeforeEach
  void setUp() {
    // For actual integration tests, you would need a Redis container
    // For unit tests, we'll create a client but not actually connect
    // In real tests, use TestContainers with Redis
  }

  @AfterEach
  void tearDown() {
    if (redisClient != null) {
      redisClient.close();
    }
  }

  @Test
  void testRedisClientCreation() {
    // Test that we can create a Redis client without throwing exceptions
    // This should not attempt connection until actually used
    assertDoesNotThrow(() -> {
      redisClient = new RedisClient(TEST_REDIS_URL);
      assertNotNull(redisClient);
    });
  }

  @Test
  void testOtpResponseSerialization() {
    // Test OTP response data class
    SqsWorker.OtpResponse response = new SqsWorker.OtpResponse(
        true, "OTP verified successfully", System.currentTimeMillis(), true);

    assertTrue(response.success);
    assertEquals("OTP verified successfully", response.message);
    assertTrue(response.received);
  }

  // Integration tests would require actual Redis instance
  // Using TestContainers for Redis integration testing

  /*
   * @Testcontainers
   * class RedisClientIntegrationTest {
   *
   * @Container
   * static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
   * .withExposedPorts(6379);
   *
   * @Test
   * void testSetAndGet() {
   * String redisUrl = String.format("redis://localhost:%d",
   * redis.getMappedPort(6379));
   * RedisClient client = new RedisClient(redisUrl);
   *
   * // Test set and get
   * SqsWorker.OtpResponse response = new SqsWorker.OtpResponse(
   * true, "Test message", System.currentTimeMillis(), true
   * );
   *
   * client.setWithTtl("test_key", response, 60);
   * SqsWorker.OtpResponse retrieved = client.get("test_key",
   * SqsWorker.OtpResponse.class);
   *
   * assertNotNull(retrieved);
   * assertEquals(response.success, retrieved.success);
   * assertEquals(response.message, retrieved.message);
   *
   * client.close();
   * }
   *
   * @Test
   * void testPubSub() throws Exception {
   * String redisUrl = String.format("redis://localhost:%d",
   * redis.getMappedPort(6379));
   * RedisClient client = new RedisClient(redisUrl);
   *
   * String channel = "test_channel";
   * SqsWorker.OtpResponse message = new SqsWorker.OtpResponse(
   * true, "Test pub/sub", System.currentTimeMillis(), true
   * );
   *
   * // Start waiting for message
   * CompletableFuture<SqsWorker.OtpResponse> future =
   * client.waitForMessage(channel, SqsWorker.OtpResponse.class,
   * Duration.ofSeconds(5));
   *
   * // Give a moment for subscription to be established
   * Thread.sleep(100);
   *
   * // Publish message
   * client.publish(channel, message);
   *
   * // Wait for result
   * SqsWorker.OtpResponse received = future.get(6, TimeUnit.SECONDS);
   *
   * assertNotNull(received);
   * assertEquals(message.success, received.success);
   * assertEquals(message.message, received.message);
   *
   * client.close();
   * }
   * }
   */
}
