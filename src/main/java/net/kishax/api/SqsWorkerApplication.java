package net.kishax.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import net.kishax.api.auth.AuthApiController;
import net.kishax.api.auth.DatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Main application class for SQS Worker
 * Entry point for standalone execution
 */
public class SqsWorkerApplication {
  private static final Logger logger = LoggerFactory.getLogger(SqsWorkerApplication.class);

  private SqsWorker sqsWorker;
  private RedisClient redisClient;
  private SqsClient sqsClient;
  private DatabaseService databaseService;
  private Javalin authApiServer;

  public static void main(String[] args) {
    SqsWorkerApplication app = new SqsWorkerApplication();
    app.run();
  }

  public void run() {
    try {
      logger.info("🎯 Starting Kishax AWS SQS Worker...");

      // Load configuration
      Configuration config = new Configuration();
      config.validate();

      if (!config.isSqsWorkerEnabled()) {
        logger.info("⏸️ SQS Worker is disabled in configuration");
        return;
      }

      // Initialize clients
      this.sqsClient = config.createSqsClient();
      this.redisClient = config.createRedisClient();

      // Initialize database service
      if (config.getDatabaseUrl() != null && config.isAuthApiEnabled()) {
        this.databaseService = new DatabaseService(config.getDatabaseUrl());
      }

      // Create WebToMcMessageSender
      WebToMcMessageSender webToMcSender = new WebToMcMessageSender(sqsClient, config.getWebToMcQueueUrl());

      // Create McToWebMessageSender
      McToWebMessageSender mcToWebSender = new McToWebMessageSender(sqsClient, config.getMcToWebQueueUrl(),
          "standalone-app");

      // Create WebApiClient
      WebApiClient webApiClient = new WebApiClient(config.getWebApiUrl(), config.getWebApiKey());

      // Start Auth API Server if enabled
      if (config.isAuthApiEnabled() && databaseService != null) {
        startAuthApiServer(config);
      }

      // Create and start SQS Worker
      this.sqsWorker = new SqsWorker(
          sqsClient,
          config.getMcToWebQueueUrl(),
          config.getQueueMode(),
          redisClient,
          webToMcSender,
          mcToWebSender,
          webApiClient,
          config);

      // Set up shutdown hook
      Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

      // Start the worker
      sqsWorker.start();

      logger.info("✅ SQS Worker started successfully");

      // Keep the application running
      Thread.currentThread().join();

    } catch (Configuration.ConfigurationException e) {
      logger.error("❌ Configuration error: {}", e.getMessage());
      System.exit(1);
    } catch (Exception e) {
      logger.error("❌ Failed to start SQS Worker: {}", e.getMessage(), e);
      System.exit(1);
    }
  }

  private void startAuthApiServer(Configuration config) {
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      objectMapper.registerModule(new JavaTimeModule());

      AuthApiController authController = new AuthApiController(
          databaseService,
          objectMapper,
          config.getAuthApiKey());

      this.authApiServer = Javalin.create(javalinConfig -> {
        javalinConfig.showJavalinBanner = false;
      }).start(config.getAuthApiPort());

      authController.setupRoutes(authApiServer);

      logger.info("✅ Auth API Server started on port {}", config.getAuthApiPort());
    } catch (Exception e) {
      logger.error("❌ Failed to start Auth API Server: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to start Auth API Server", e);
    }
  }

  private void shutdown() {
    logger.info("🔄 Received shutdown signal, shutting down gracefully...");

    try {
      if (authApiServer != null) {
        authApiServer.stop();
        logger.info("✅ Auth API Server stopped");
      }

      if (sqsWorker != null) {
        sqsWorker.stop();
        logger.info("✅ SQS Worker stopped");
      }

      if (databaseService != null) {
        databaseService.close();
        logger.info("✅ Database service closed");
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
