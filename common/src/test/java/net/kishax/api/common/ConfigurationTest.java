package net.kishax.api.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {

  private Configuration configuration;

  @BeforeEach
  void setUp() {
    configuration = new Configuration();
  }

  @AfterEach
  void tearDown() {
    // Clean up any environment variables we set during tests
    System.clearProperty("test.property");
  }

  @Test
  void testGetPropertyWithDefault() {
    String value = configuration.getProperty("non.existent.property", "default-value");
    assertEquals("default-value", value);
  }

  @Test
  void testGetPropertyWithEnvironmentVariable() {
    // Set an environment variable (this is tricky in JUnit, so we'll test the
    // fallback behavior)
    String value = configuration.getProperty("PATH"); // PATH should exist on most systems
    assertNotNull(value);
    assertFalse(value.isEmpty());
  }

  @Test
  void testGetIntProperty() {
    // Test with default value
    int value = configuration.getIntProperty("non.existent.int", 42);
    assertEquals(42, value);
  }

  @Test
  void testGetBooleanProperty() {
    // Test with default value
    boolean value = configuration.getBooleanProperty("non.existent.boolean", true);
    assertTrue(value);
  }

  @Test
  void testDefaultValues() {
    // Test that default values are returned for various configuration keys
    assertEquals("ap-northeast-1", configuration.getAwsRegion());
    assertEquals("redis://localhost:6379", configuration.getRedisUrl());
    assertTrue(configuration.isSqsWorkerEnabled());
    assertEquals(5, configuration.getSqsWorkerPollingInterval());
    assertEquals(10, configuration.getSqsWorkerMaxMessages());
    assertEquals(20, configuration.getSqsWorkerWaitTime());
    assertEquals(30, configuration.getSqsWorkerVisibilityTimeout());
    assertEquals(5000, configuration.getRedisConnectionTimeout());
    assertEquals(3000, configuration.getRedisCommandTimeout());
    assertEquals(10, configuration.getShutdownGracePeriod());
  }

  @Test
  void testValidationWithNoErrors() {
    // Base Configuration validation should not throw exceptions (empty
    // implementation)
    assertDoesNotThrow(() -> configuration.validate());
  }

  @Test
  void testCreateSqsClientWithoutCredentials() {
    // This should throw an exception because credentials are not configured
    assertThrows(Exception.class, () -> {
      configuration.createSqsClient();
    });
  }

  // Test with mock environment variables would require additional setup
  // For now, we focus on testing the basic functionality and defaults
}
