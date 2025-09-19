package net.kishax.api.bridge;

import net.kishax.api.common.Configuration;

/**
 * Configuration class specifically for SQS-Redis Bridge application
 * Validates only bridge-specific requirements
 */
public class BridgeConfiguration extends Configuration {

  @Override
  public void validate() throws ConfigurationException {
    StringBuilder errors = new StringBuilder();

    // AWS configuration
    if (getAwsAccessKeyId() == null || getAwsAccessKeyId().trim().isEmpty()) {
      errors.append("AWS Access Key ID is required\n");
    }
    if (getAwsSecretAccessKey() == null || getAwsSecretAccessKey().trim().isEmpty()) {
      errors.append("AWS Secret Access Key is required\n");
    }
    if (getToWebQueueUrl() == null || getToWebQueueUrl().trim().isEmpty()) {
      errors.append("To Web Queue URL is required\n");
    }
    if (getToMcQueueUrl() == null || getToMcQueueUrl().trim().isEmpty()) {
      errors.append("To MC Queue URL is required\n");
    }

    // Redis configuration
    if (getRedisUrl() == null || getRedisUrl().trim().isEmpty()) {
      errors.append("Redis URL is required\n");
    }

    if (errors.length() > 0) {
      throw new ConfigurationException("SQS Bridge configuration validation failed:\n" + errors.toString());
    }

    logger.info("✅ SQS Bridge configuration validation passed");
  }
}
