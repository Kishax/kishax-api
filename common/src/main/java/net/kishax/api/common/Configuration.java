package net.kishax.api.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration management for Kishax AWS Integration
 */
public class Configuration {
  protected static final Logger logger = LoggerFactory.getLogger(Configuration.class);

  private final Properties properties;

  public Configuration() {
    this.properties = new Properties();
    loadProperties();
  }

  private void loadProperties() {
    // Load from application.properties
    try (InputStream is = getClass().getClassLoader().getResourceAsStream("application.properties")) {
      if (is != null) {
        properties.load(is);
        logger.info("✅ Loaded configuration from application.properties");
      } else {
        logger.warn("⚠️ application.properties not found, using environment variables only");
      }
    } catch (IOException e) {
      logger.error("❌ Error loading application.properties: {}", e.getMessage(), e);
    }
  }

  /**
   * Get property with environment variable fallback
   */
  public String getProperty(String key, String defaultValue) {
    // First check environment variables
    String envValue = System.getenv(key.toUpperCase().replace(".", "_"));
    if (envValue != null && !envValue.trim().isEmpty()) {
      return envValue;
    }

    // Then check properties file
    String propValue = properties.getProperty(key);
    if (propValue != null && !propValue.trim().isEmpty() && !propValue.startsWith("${")) {
      return propValue;
    }

    // Finally use default value
    return defaultValue;
  }

  public String getProperty(String key) {
    return getProperty(key, null);
  }

  public int getIntProperty(String key, int defaultValue) {
    String value = getProperty(key);
    if (value == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      logger.warn("⚠️ Invalid integer value for {}: {}, using default: {}", key, value, defaultValue);
      return defaultValue;
    }
  }

  public boolean getBooleanProperty(String key, boolean defaultValue) {
    String value = getProperty(key);
    if (value == null) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value);
  }

  // AWS Configuration
  public String getAwsRegion() {
    return getProperty("aws.region", "ap-northeast-1");
  }

  public String getAwsAccessKeyId() {
    return getProperty("mc.web.sqs.access.key.id");
  }

  public String getAwsSecretAccessKey() {
    return getProperty("mc.web.sqs.secret.access.key");
  }

  public String getMcToWebQueueUrl() {
    return getProperty("mc.to.web.queue.url");
  }

  public String getWebToMcQueueUrl() {
    return getProperty("web.to.mc.queue.url");
  }

  // Redis Configuration
  public String getRedisUrl() {
    return getProperty("redis.url", "redis://localhost:6379");
  }

  // Discord Configuration
  public String getDiscordToken() {
    return getProperty("discord.token");
  }

  public String getDiscordChannelId() {
    return getProperty("discord.channel.id");
  }

  public String getDiscordChatChannelId() {
    return getProperty("discord.chat.channel.id");
  }

  public String getDiscordAdminChannelId() {
    return getProperty("discord.admin.channel.id");
  }

  public String getDiscordRuleChannelId() {
    return getProperty("discord.rule.channel.id");
  }

  public String getDiscordRuleMessageId() {
    return getProperty("discord.rule.message.id");
  }

  public String getDiscordPresenceActivity() {
    return getProperty("discord.presence.activity", "Kishaxサーバー");
  }

  public long getDiscordGuildId() {
    String value = getProperty("discord.guild.id");
    if (value == null) {
      return 0L;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      logger.warn("⚠️ Invalid long value for discord.guild.id: {}, using default: 0", value);
      return 0L;
    }
  }

  public String getBEDefaultEmojiName() {
    return getProperty("discord.emoji.default.name", "steve");
  }

  // SQS Configuration for Discord
  public String getSqsQueueUrl() {
    return getProperty("aws.sqs.queue.url");
  }

  public int getSqsMaxMessages() {
    return getIntProperty("aws.sqs.max.messages", 10);
  }

  public int getSqsWaitTimeSeconds() {
    return getIntProperty("aws.sqs.wait.time.seconds", 20);
  }

  public int getRedisConnectionTimeout() {
    return getIntProperty("redis.connectionTimeout", 5000);
  }

  public int getRedisCommandTimeout() {
    return getIntProperty("redis.commandTimeout", 3000);
  }

  // Queue Mode Configuration
  public String getQueueMode() {
    return getProperty("queue.mode", "MC").toUpperCase();
  }

  // SQS Worker Configuration
  public boolean isSqsWorkerEnabled() {
    return getBooleanProperty("sqs.worker.enabled", true);
  }

  public int getSqsWorkerPollingInterval() {
    return getIntProperty("sqs.worker.pollingIntervalSeconds", 5);
  }

  public int getSqsWorkerMaxMessages() {
    return getIntProperty("sqs.worker.maxMessages", 10);
  }

  public int getSqsWorkerWaitTime() {
    return getIntProperty("sqs.worker.waitTimeSeconds", 20);
  }

  // Auth API Configuration
  public boolean isAuthApiEnabled() {
    return getBooleanProperty("auth.api.enabled", true);
  }

  public int getAuthApiPort() {
    return getIntProperty("auth.api.port", 8080);
  }

  public String getAuthApiKey() {
    return getProperty("auth.api.key");
  }

  // Database Configuration
  public String getDatabaseUrl() {
    return getProperty("database.url");
  }

  public int getSqsWorkerVisibilityTimeout() {
    return getIntProperty("sqs.worker.visibilityTimeoutSeconds", 30);
  }

  // Application Configuration
  public int getShutdownGracePeriod() {
    return getIntProperty("app.shutdownGracePeriodSeconds", 10);
  }

  /**
   * Validate configuration - override in application-specific classes
   */
  public void validate() throws ConfigurationException {
    // Empty implementation - to be overridden by specific applications
    logger.info("✅ Configuration validation completed (no validation rules defined)");
  }

  /**
   * Create configured SQS client
   */
  public SqsClient createSqsClient() {
    return SqsClient.builder()
        .region(Region.of(getAwsRegion()))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(getAwsAccessKeyId(), getAwsSecretAccessKey())))
        .build();
  }

  /**
   * Get the appropriate queue URL based on QUEUE_MODE
   * MC mode: polls from webToMcQueue
   * WEB mode: polls from mcToWebQueue
   * DISCORD mode: polls from dedicated Discord queue
   */
  public String getPollingQueueUrl() {
    String queueMode = getQueueMode();
    switch (queueMode) {
      case "MC":
        return getWebToMcQueueUrl();
      case "WEB":
        return getMcToWebQueueUrl();
      case "DISCORD":
        return getSqsQueueUrl(); // Discord-specific queue
      default:
        logger.warn("⚠️ Unknown QUEUE_MODE: {}, defaulting to MC mode", queueMode);
        return getWebToMcQueueUrl();
    }
  }

  /**
   * Get the appropriate sending queue URL based on QUEUE_MODE
   * MC mode: sends to mcToWebQueue
   * WEB mode: sends to webToMcQueue
   * DISCORD mode: sends responses back to original source queues
   */
  public String getSendingQueueUrl() {
    String queueMode = getQueueMode();
    switch (queueMode) {
      case "MC":
        return getMcToWebQueueUrl();
      case "WEB":
        return getWebToMcQueueUrl();
      case "DISCORD":
        // Discord doesn't send to SQS directly, uses Redis pub/sub instead
        return null;
      default:
        logger.warn("⚠️ Unknown QUEUE_MODE: {}, defaulting to MC mode", queueMode);
        return getMcToWebQueueUrl();
    }
  }

  /**
   * Configuration exception
   */
  public static class ConfigurationException extends Exception {
    public ConfigurationException(String message) {
      super(message);
    }
  }
}
