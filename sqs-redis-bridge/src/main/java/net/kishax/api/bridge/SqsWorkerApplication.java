package net.kishax.api.bridge;

import net.kishax.api.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Main application class for SQS-Redis Bridge
 * Entry point for standalone execution
 */
public class SqsWorkerApplication {
  private static final Logger logger = LoggerFactory.getLogger(SqsWorkerApplication.class);

  private SqsWorker sqsWorker;
  private RedisClient redisClient;
  private SqsClient sqsClient;
  private DiscordMessageHandler discordMessageHandler;
  private DiscordResponseHandler discordResponseHandler;

  public static void main(String[] args) {
    SqsWorkerApplication app = new SqsWorkerApplication();
    app.run();
  }

  public void run() {
    try {
      logger.info("🎯 Starting Kishax SQS-Redis Bridge...");

      // Load configuration
      Configuration config = new BridgeConfiguration();
      config.validate();

      if (!config.isSqsWorkerEnabled()) {
        logger.info("⏸️ SQS Worker is disabled in configuration");
        return;
      }

      // Initialize clients
      this.sqsClient = config.createSqsClient();
      this.redisClient = new RedisClient(config.getRedisUrl());

      // Create WebToMcMessageSender
      WebToMcMessageSender webToMcSender = new WebToMcMessageSender(sqsClient, config.getWebToMcQueueUrl());

      // Create McToWebMessageSender
      McToWebMessageSender mcToWebSender = new McToWebMessageSender(sqsClient, config.getMcToWebQueueUrl(),
          "sqs-redis-bridge");

      // Create Discord handlers
      this.discordMessageHandler = new DiscordMessageHandler(redisClient);
      this.discordResponseHandler = new DiscordResponseHandler(redisClient, mcToWebSender, webToMcSender);

      // Start Discord response subscription
      discordResponseHandler.startSubscription();

      // Create and start SQS Worker
      this.sqsWorker = new SqsWorker(
          sqsClient,
          config.getPollingQueueUrl(),
          config.getQueueMode(),
          redisClient,
          webToMcSender,
          mcToWebSender,
          config);

      // Set up shutdown hook
      Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

      // Start the worker
      sqsWorker.start();

      logger.info("✅ SQS-Redis Bridge started successfully");

      // Keep the application running
      Thread.currentThread().join();

    } catch (Configuration.ConfigurationException e) {
      logger.error("❌ Configuration error: {}", e.getMessage());
      System.exit(1);
    } catch (Exception e) {
      logger.error("❌ Failed to start SQS-Redis Bridge: {}", e.getMessage(), e);
      System.exit(1);
    }
  }

  private void shutdown() {
    logger.info("🔄 Received shutdown signal, shutting down gracefully...");

    try {
      if (sqsWorker != null) {
        sqsWorker.stop();
        logger.info("✅ SQS Worker stopped");
      }

      if (redisClient != null) {
        redisClient.close();
        logger.info("✅ Redis client closed");
      }

      if (sqsClient != null) {
        sqsClient.close();
        logger.info("✅ SQS client closed");
      }

      logger.info("🏁 Shutdown completed successfully");

    } catch (Exception e) {
      logger.error("❌ Error during shutdown: {}", e.getMessage(), e);
    }
  }
}
