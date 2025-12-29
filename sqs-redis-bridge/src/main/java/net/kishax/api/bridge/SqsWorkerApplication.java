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

  private SqsWorker toMcWorker;  // Web → MC のメッセージを処理
  private SqsWorker toWebWorker; // MC → Web のレスポンスを処理
  private RedisClient redisClient;
  private SqsClient sqsClient;
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
      WebToMcMessageSender webToMcSender = new WebToMcMessageSender(sqsClient, config.getToMcQueueUrl());

      // Create McToWebMessageSender
      McToWebMessageSender mcToWebSender = new McToWebMessageSender(sqsClient, config.getToWebQueueUrl(),
          "sqs-redis-bridge");

      // Create Discord handlers
      this.discordResponseHandler = new DiscordResponseHandler(redisClient, mcToWebSender, webToMcSender);

      // Start Discord response subscription
      discordResponseHandler.startSubscription();

      // Set up shutdown hook
      Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

      String queueMode = config.getQueueMode();
      logger.info("🔧 Queue mode: {}", queueMode);

      // WEB mode: Poll to-web queue + Subscribe to Redis Pub/Sub
      // MC mode: Poll to-mc queue
      if ("WEB".equalsIgnoreCase(queueMode)) {
        logger.info("🌐 Starting WEB mode: Polling to-web queue + Redis Pub/Sub subscription");
        
        // Single worker for WEB mode:
        // - Polls to-web queue (MC → Web responses)
        // - Subscribes to Redis Pub/Sub web_to_mc (Web → MC messages)
        this.toMcWorker = new SqsWorker(
            sqsClient,
            config.getToWebQueueUrl(),  // Poll to-web queue (MC → Web)
            queueMode,  // WEB mode - will subscribe to Redis
            redisClient,
            webToMcSender,
            mcToWebSender,
            config);
        toMcWorker.start();
        logger.info("✅ WEB Worker started:");
        logger.info("   📥 Polling to-web queue for MC responses");
        logger.info("   📡 Subscribed to Redis Pub/Sub for Web messages");

      } else {
        // MC mode: Only process to-web queue
        logger.info("🎮 Starting MC mode with single SQS worker...");
        this.toMcWorker = new SqsWorker(
            sqsClient,
            config.getPollingQueueUrl(),
            queueMode,
            redisClient,
            webToMcSender,
            mcToWebSender,
            config);
        toMcWorker.start();
        logger.info("✅ Worker started: Polling {} queue", config.getPollingQueueUrl());
      }

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
      if (toMcWorker != null) {
        toMcWorker.stop();
        logger.info("✅ SQS Worker stopped");
      }

      if (toWebWorker != null) {
        toWebWorker.stop();
        logger.info("✅ Additional Worker stopped");
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
