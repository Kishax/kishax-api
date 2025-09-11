package net.kishax.aws;

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
  private static final Logger logger = LoggerFactory.getLogger(Configuration.class);

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
    return getProperty("aws.sqs.accessKeyId");
  }

  public String getAwsSecretAccessKey() {
    return getProperty("aws.sqs.secretAccessKey");
  }

  public String getMcToWebQueueUrl() {
    return getProperty("aws.sqs.mcToWebQueueUrl");
  }

  public String getWebToMcQueueUrl() {
    return getProperty("aws.sqs.webToMcQueueUrl");
  }

  // Redis Configuration
  public String getRedisUrl() {
    return getProperty("redis.url", "redis://localhost:6379");
  }

  public int getRedisConnectionTimeout() {
    return getIntProperty("redis.connectionTimeout", 5000);
  }

  public int getRedisCommandTimeout() {
    return getIntProperty("redis.commandTimeout", 3000);
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

  public int getSqsWorkerVisibilityTimeout() {
    return getIntProperty("sqs.worker.visibilityTimeoutSeconds", 30);
  }

  // Application Configuration
  public int getShutdownGracePeriod() {
    return getIntProperty("app.shutdownGracePeriodSeconds", 10);
  }

  /**
   * Validate required configuration
   */
  public void validate() throws ConfigurationException {
    StringBuilder errors = new StringBuilder();

    // AWS configuration
    if (getAwsAccessKeyId() == null || getAwsAccessKeyId().trim().isEmpty()) {
      errors.append("AWS Access Key ID is required\n");
    }
    if (getAwsSecretAccessKey() == null || getAwsSecretAccessKey().trim().isEmpty()) {
      errors.append("AWS Secret Access Key is required\n");
    }
    if (getMcToWebQueueUrl() == null || getMcToWebQueueUrl().trim().isEmpty()) {
      errors.append("MC to Web Queue URL is required\n");
    }
    if (getWebToMcQueueUrl() == null || getWebToMcQueueUrl().trim().isEmpty()) {
      errors.append("Web to MC Queue URL is required\n");
    }

    // Redis configuration
    if (getRedisUrl() == null || getRedisUrl().trim().isEmpty()) {
      errors.append("Redis URL is required\n");
    }

    // Web API configuration - not required anymore since we use Redis only

    if (errors.length() > 0) {
      throw new ConfigurationException("Configuration validation failed:\n" + errors.toString());
    }

    logger.info("✅ Configuration validation passed");
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
   * Create configured Redis client
   */
  public RedisClient createRedisClient() {
    return new RedisClient(getRedisUrl());
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
