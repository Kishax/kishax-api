package net.kishax.api.auth;

import net.kishax.api.common.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main application class for MC Authentication API
 * Entry point for standalone execution
 */
public class McAuthApplication {
  private static final Logger logger = LoggerFactory.getLogger(McAuthApplication.class);

  private DatabaseService databaseService;
  private Javalin authApiServer;

  public static void main(String[] args) {
    McAuthApplication app = new McAuthApplication();
    app.run();
  }

  public void run() {
    try {
      logger.info("🎯 Starting Kishax MC Authentication API...");

      // Load configuration
      Configuration config = new AuthConfiguration();
      config.validate();

      if (!config.isAuthApiEnabled()) {
        logger.info("⏸️ Auth API is disabled in configuration");
        return;
      }

      // Initialize database service
      if (config.getDatabaseUrl() == null) {
        logger.error("❌ Database URL is required for Auth API");
        System.exit(1);
      }

      this.databaseService = new DatabaseService(config.getDatabaseUrl());

      // Start Auth API Server
      startAuthApiServer(config);

      // Set up shutdown hook
      Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

      logger.info("✅ MC Authentication API started successfully on port {}", config.getAuthApiPort());

      // Keep the application running
      Thread.currentThread().join();

    } catch (Configuration.ConfigurationException e) {
      logger.error("❌ Configuration error: {}", e.getMessage());
      System.exit(1);
    } catch (Exception e) {
      logger.error("❌ Failed to start MC Authentication API: {}", e.getMessage(), e);
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

      if (databaseService != null) {
        databaseService.close();
        logger.info("✅ Database service closed");
      }

      logger.info("🏁 Shutdown completed successfully");

    } catch (Exception e) {
      logger.error("❌ Error during shutdown: {}", e.getMessage(), e);
    }
  }
}
