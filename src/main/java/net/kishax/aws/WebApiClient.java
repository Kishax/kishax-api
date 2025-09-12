package net.kishax.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * HTTP client for WEB API integration
 */
public class WebApiClient {
  private static final Logger logger = LoggerFactory.getLogger(WebApiClient.class);

  private final HttpClient httpClient;
  private final String webApiUrl;
  private final String webApiKey;
  private final ObjectMapper objectMapper;

  public WebApiClient(String webApiUrl, String webApiKey) {
    this.webApiUrl = webApiUrl;
    this.webApiKey = webApiKey;
    this.httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)  // Force HTTP/1.1
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    this.objectMapper = new ObjectMapper();
    this.objectMapper.registerModule(new JavaTimeModule());
  }

  /**
   * Send auth token to WEB API
   */
  public void sendAuthToken(String mcid, String uuid, String authToken, long expiresAt, String action) {
    try {
      ObjectNode payload = objectMapper.createObjectNode();
      payload.put("mcid", mcid);
      payload.put("uuid", uuid);
      payload.put("authToken", authToken);
      payload.put("expiresAt", Instant.ofEpochMilli(expiresAt).toString());
      payload.put("action", action);

      String requestBody = objectMapper.writeValueAsString(payload);
      
      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
          .uri(URI.create(webApiUrl + "/api/mc/auth-token"))
          .version(HttpClient.Version.HTTP_1_1)  // Ensure HTTP/1.1
          .header("Content-Type", "application/json")
          .header("User-Agent", "Kishax-AWS-Client/1.0.2")
          .POST(HttpRequest.BodyPublishers.ofString(requestBody))
          .timeout(Duration.ofSeconds(30));

      // Add API key if provided
      if (webApiKey != null && !webApiKey.isEmpty()) {
        requestBuilder.header("X-API-Key", webApiKey);
      }

      HttpRequest request = requestBuilder.build();

      logger.info("📤 Sending auth token to WEB API: {} for player {}", webApiUrl + "/api/mc/auth-token", mcid);
      logger.debug("🔍 Request headers: {}", request.headers().map());
      logger.debug("🔍 Request body: {}", requestBody);
      logger.debug("🔍 Request URI: {}", request.uri());

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      
      logger.info("📥 Received response: {} - Content-Length: {}", 
          response.statusCode(), 
          response.headers().firstValue("Content-Length").orElse("unknown"));
      logger.debug("📥 Response headers: {}", response.headers().map());
      logger.debug("📥 Response body: {}", response.body());

      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        logger.info("✅ Auth token sent to WEB API successfully: {} - {}", response.statusCode(), mcid);
      } else {
        logger.error("❌ WEB API returned error status: {} - Body: {}", response.statusCode(), response.body());
      }

    } catch (IOException e) {
      logger.error("❌ IO Error sending auth token to WEB API: {}", e.getMessage());
      logger.error("❌ Exception class: {}", e.getClass().getSimpleName());
      if (e.getCause() != null) {
        logger.error("❌ Root cause: {} - {}", e.getCause().getClass().getSimpleName(), e.getCause().getMessage());
      }
      logger.debug("❌ Full stack trace:", e);
    } catch (InterruptedException e) {
      logger.error("❌ Request interrupted: {}", e.getMessage());
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      logger.error("❌ Unexpected error sending auth token to WEB API: {} ({})", e.getMessage(), e.getClass().getSimpleName());
      logger.debug("❌ Full stack trace:", e);
    }
  }

  /**
   * Test connection to WEB API
   */
  public boolean testConnection() {
    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(webApiUrl + "/api/health"))
          .GET()
          .timeout(Duration.ofSeconds(10))
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      
      boolean isHealthy = response.statusCode() >= 200 && response.statusCode() < 300;
      logger.info("🔍 WEB API health check: {} - Status: {}", webApiUrl, response.statusCode());
      
      return isHealthy;
    } catch (Exception e) {
      logger.warn("⚠️ WEB API health check failed: {}", e.getMessage());
      return false;
    }
  }
}