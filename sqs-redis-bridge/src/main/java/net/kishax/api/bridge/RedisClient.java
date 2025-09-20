package net.kishax.api.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Redis client for Pub/Sub communication and data storage
 * Provides abstraction over Lettuce Redis client
 */
public class RedisClient {
  private static final Logger logger = LoggerFactory.getLogger(RedisClient.class);

  private final io.lettuce.core.RedisClient lettuceClient;
  private StatefulRedisConnection<String, String> connection;
  private RedisCommands<String, String> syncCommands;
  private final ObjectMapper objectMapper;

  public RedisClient(String redisUrl) {
    this.lettuceClient = io.lettuce.core.RedisClient.create(redisUrl);
    this.objectMapper = new ObjectMapper();
    this.objectMapper.registerModule(new JavaTimeModule());

    logger.info("✅ Redis client initialized (connection will be established on first use)");
  }

  private void ensureConnection() {
    if (connection == null) {
      try {
        logger.debug("🔗 Attempting Redis connection to: {}", lettuceClient.getOptions().toString());
        connection = lettuceClient.connect();
        syncCommands = connection.sync();

        // Test the connection with a ping
        String pongResponse = syncCommands.ping();
        logger.debug("✅ Redis connection established successfully. Ping response: {}", pongResponse);
      } catch (Exception e) {
        logger.error("❌ Failed to establish Redis connection: {}", e.getMessage(), e);
        throw e;
      }
    }
  }

  /**
   * Set a value with TTL
   */
  public void setWithTtl(String key, Object value, long ttlSeconds) {
    try {
      ensureConnection();
      String jsonValue = objectMapper.writeValueAsString(value);
      syncCommands.setex(key, ttlSeconds, jsonValue);
      logger.debug("📝 Stored in Redis: {} (TTL: {}s)", key, ttlSeconds);
    } catch (Exception e) {
      logger.error("❌ Error storing value in Redis: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to store value in Redis", e);
    }
  }

  /**
   * Get a value and deserialize to specified class
   */
  public <T> T get(String key, Class<T> valueType) {
    try {
      ensureConnection();
      String jsonValue = syncCommands.get(key);
      if (jsonValue == null) {
        return null;
      }
      return objectMapper.readValue(jsonValue, valueType);
    } catch (Exception e) {
      logger.error("❌ Error retrieving value from Redis: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to retrieve value from Redis", e);
    }
  }

  /**
   * Get a value as string
   */
  public String getString(String key) {
    try {
      ensureConnection();
      return syncCommands.get(key);
    } catch (Exception e) {
      logger.error("❌ Error retrieving string from Redis: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to retrieve string from Redis", e);
    }
  }

  /**
   * Delete a key
   */
  public void delete(String key) {
    try {
      ensureConnection();
      syncCommands.del(key);
      logger.debug("🗑️ Deleted from Redis: {}", key);
    } catch (Exception e) {
      logger.error("❌ Error deleting key from Redis: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to delete key from Redis", e);
    }
  }

  /**
   * Check if key exists
   */
  public boolean exists(String key) {
    try {
      ensureConnection();
      return syncCommands.exists(key) > 0;
    } catch (Exception e) {
      logger.error("❌ Error checking key existence in Redis: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to check key existence in Redis", e);
    }
  }

  /**
   * Publish a message to a channel
   */
  public void publish(String channel, Object message) {
    try {
      ensureConnection();
      String jsonMessage = objectMapper.writeValueAsString(message);
      long subscriberCount = syncCommands.publish(channel, jsonMessage);
      logger.debug("📡 Published to Redis channel {} (subscribers: {}): {}", channel, subscriberCount, jsonMessage);
    } catch (Exception e) {
      logger.error("❌ Error publishing to Redis channel: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to publish to Redis channel", e);
    }
  }

  /**
   * Subscribe to a channel and wait for a message with timeout
   * Returns CompletableFuture that completes when message is received or timeout
   * occurs
   */
  public <T> CompletableFuture<T> waitForMessage(String channel, Class<T> messageType, Duration timeout) {
    return CompletableFuture.supplyAsync(() -> {
      StatefulRedisPubSubConnection<String, String> pubSubConnection = null;
      try {
        pubSubConnection = lettuceClient.connectPubSub();
        RedisPubSubCommands<String, String> pubSubCommands = pubSubConnection.sync();

        final CompletableFuture<T> resultFuture = new CompletableFuture<>();
        final StatefulRedisPubSubConnection<String, String> finalConnection = pubSubConnection;

        // Set up message listener
        pubSubConnection.addListener(new io.lettuce.core.pubsub.RedisPubSubAdapter<String, String>() {
          @Override
          public void message(String channel, String message) {
            try {
              T parsedMessage = objectMapper.readValue(message, messageType);
              resultFuture.complete(parsedMessage);
            } catch (Exception e) {
              logger.error("❌ Error parsing Redis message: {}", e.getMessage(), e);
              resultFuture.completeExceptionally(e);
            } finally {
              // Cleanup
              try {
                finalConnection.close();
              } catch (Exception e) {
                logger.warn("Warning: Failed to close pub/sub connection: {}", e.getMessage());
              }
            }
          }
        });

        // Subscribe to channel
        pubSubCommands.subscribe(channel);
        logger.info("🔔 Subscribed to Redis channel: {}", channel);

        // Wait for result or timeout
        try {
          return resultFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
          logger.info("⏰ Timeout waiting for message on channel: {}", channel);
          return null;
        } finally {
          // Ensure connection is closed
          try {
            if (finalConnection != null && finalConnection.isOpen()) {
              finalConnection.close();
            }
          } catch (Exception e) {
            logger.warn("Warning: Failed to close pub/sub connection in finally block: {}", e.getMessage());
          }
        }

      } catch (Exception e) {
        logger.error("❌ Error waiting for Redis message: {}", e.getMessage(), e);
        if (pubSubConnection != null) {
          try {
            pubSubConnection.close();
          } catch (Exception closeE) {
            logger.warn("Warning: Failed to close pub/sub connection after error: {}", closeE.getMessage());
          }
        }
        throw new RuntimeException("Failed to wait for Redis message", e);
      }
    });
  }

  /**
   * Subscribe to a channel with a callback
   * Returns a subscription handle that can be used to unsubscribe
   */
  public RedisSubscription subscribe(String channel, Consumer<String> messageHandler) {
    try {
      StatefulRedisPubSubConnection<String, String> pubSubConnection = lettuceClient.connectPubSub();
      RedisPubSubCommands<String, String> pubSubCommands = pubSubConnection.sync();

      // Set up message listener
      pubSubConnection.addListener(new io.lettuce.core.pubsub.RedisPubSubAdapter<String, String>() {
        @Override
        public void message(String receivedChannel, String message) {
          if (channel.equals(receivedChannel)) {
            try {
              messageHandler.accept(message);
            } catch (Exception e) {
              logger.error("❌ Error in message handler for channel {}: {}", channel, e.getMessage(), e);
            }
          }
        }
      });

      // Subscribe to channel
      pubSubCommands.subscribe(channel);
      logger.info("🔔 Subscribed to Redis channel: {}", channel);

      return new RedisSubscription(pubSubConnection, channel);

    } catch (Exception e) {
      logger.error("❌ Error subscribing to Redis channel: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to subscribe to Redis channel", e);
    }
  }

  /**
   * Send auth token message to Web via Redis
   */
  public void sendAuthToken(String mcid, String uuid, String authToken, long expiresAt, String action) {
    try {
      // Create message object
      Map<String, Object> message = new HashMap<>();
      message.put("type", "mc_auth_token");
      message.put("source", "api");
      message.put("timestamp", Instant.now().toString());

      Map<String, Object> data = new HashMap<>();
      data.put("mcid", mcid);
      data.put("uuid", uuid);
      data.put("authToken", authToken);
      data.put("expiresAt", Instant.ofEpochMilli(expiresAt).toString());
      data.put("action", action);
      message.put("data", data);

      // Publish to mc_to_web channel (same channel Web is listening to)
      publish("mc_to_web", message);

      logger.info("✅ Auth token sent via Redis for player: {} - Action: {}", mcid, action);

    } catch (Exception e) {
      logger.error("❌ Failed to send auth token via Redis for player {}: {}", mcid, e.getMessage(), e);
      throw new RuntimeException("Failed to send auth token via Redis", e);
    }
  }

  /**
   * Close the Redis client
   */
  public void close() {
    try {
      if (connection != null) {
        connection.close();
      }
      if (lettuceClient != null) {
        lettuceClient.shutdown();
      }
      logger.info("📴 Redis client closed");
    } catch (Exception e) {
      logger.error("❌ Error closing Redis client: {}", e.getMessage(), e);
    }
  }

  /**
   * Subscription handle for managing Redis subscriptions
   */
  public static class RedisSubscription {
    private final StatefulRedisPubSubConnection<String, String> connection;
    private final String channel;
    private final Logger logger = LoggerFactory.getLogger(RedisSubscription.class);

    private RedisSubscription(StatefulRedisPubSubConnection<String, String> connection, String channel) {
      this.connection = connection;
      this.channel = channel;
    }

    /**
     * Unsubscribe from the channel and close the connection
     */
    public void unsubscribe() {
      try {
        if (connection != null && connection.isOpen()) {
          RedisPubSubCommands<String, String> commands = connection.sync();
          commands.unsubscribe(channel);
          connection.close();
          logger.info("🔇 Unsubscribed from Redis channel: {}", channel);
        }
      } catch (Exception e) {
        logger.error("❌ Error unsubscribing from Redis channel: {}", e.getMessage(), e);
      }
    }
  }
}
