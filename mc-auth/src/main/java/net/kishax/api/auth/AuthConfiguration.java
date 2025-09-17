package net.kishax.api.auth;

import net.kishax.api.common.Configuration;

/**
 * Configuration class specifically for MC Auth application
 * Validates only auth-specific requirements
 */
public class AuthConfiguration extends Configuration {

  @Override
  public void validate() throws ConfigurationException {
    StringBuilder errors = new StringBuilder();

    // Auth API configuration
    if (getAuthApiKey() == null || getAuthApiKey().trim().isEmpty()) {
      errors.append("Auth API Key is required\n");
    }

    // Database configuration
    if (getDatabaseUrl() == null || getDatabaseUrl().trim().isEmpty()) {
      errors.append("Database URL is required\n");
    }

    if (errors.length() > 0) {
      throw new ConfigurationException("MC Auth configuration validation failed:\n" + errors.toString());
    }

    logger.info("✅ MC Auth configuration validation passed");
  }
}
