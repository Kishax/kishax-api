package net.kishax.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Database client for interacting with the web application's database
 * Uses HTTP API calls to Next.js backend instead of direct DB access
 */
public class DatabaseClient {
  private static final Logger logger = LoggerFactory.getLogger(DatabaseClient.class);

  private final String webApiBaseUrl;
  private final CloseableHttpClient httpClient;
  private final ObjectMapper objectMapper;

  public DatabaseClient(String webApiBaseUrl) {
    this.webApiBaseUrl = webApiBaseUrl.endsWith("/") ? webApiBaseUrl.substring(0, webApiBaseUrl.length() - 1)
        : webApiBaseUrl;
    this.httpClient = HttpClients.createDefault();
    this.objectMapper = new ObjectMapper();
    this.objectMapper.registerModule(new JavaTimeModule());

    logger.info("✅ Database client initialized with base URL: {}", this.webApiBaseUrl);
  }

  /**
   * Upsert MinecraftPlayer record via Web API
   */
  public void upsertMinecraftPlayer(String mcid, String uuid, String authToken, Instant tokenExpires) {
    try {
      String endpoint = webApiBaseUrl + "/api/internal/minecraft-player/upsert";

      // Create request payload
      ObjectNode payload = objectMapper.createObjectNode();
      payload.put("mcid", mcid);
      payload.put("uuid", uuid);
      payload.put("authToken", authToken);
      payload.put("tokenExpires", tokenExpires.toEpochMilli());
      payload.put("confirmed", false);

      // Create HTTP request
      HttpPost httpPost = new HttpPost(endpoint);
      httpPost.setHeader("Content-Type", "application/json");
      httpPost.setHeader("Authorization", "Bearer " + getInternalApiKey());
      httpPost.setEntity(new StringEntity(objectMapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));

      // Execute request
      try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
        int statusCode = response.getCode();

        if (statusCode >= 200 && statusCode < 300) {
          logger.info("✅ Successfully upserted MinecraftPlayer: {} ({})", mcid, uuid);
        } else {
          String responseBody = new String(response.getEntity().getContent().readAllBytes());
          logger.error("❌ Failed to upsert MinecraftPlayer. Status: {}, Response: {}", statusCode, responseBody);
          throw new RuntimeException("Failed to upsert MinecraftPlayer: HTTP " + statusCode);
        }
      }

    } catch (Exception e) {
      logger.error("❌ Error upserting MinecraftPlayer: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to upsert MinecraftPlayer", e);
    }
  }

  /**
   * Update MinecraftPlayer OTP via Web API
   */
  public void updatePlayerOtp(String mcid, String otp, Instant otpExpires) {
    try {
      String endpoint = webApiBaseUrl + "/api/internal/minecraft-player/update-otp";

      // Create request payload
      ObjectNode payload = objectMapper.createObjectNode();
      payload.put("mcid", mcid);
      payload.put("otp", otp);
      payload.put("otpExpires", otpExpires.toEpochMilli());

      // Create HTTP request
      HttpPut httpPut = new HttpPut(endpoint);
      httpPut.setHeader("Content-Type", "application/json");
      httpPut.setHeader("Authorization", "Bearer " + getInternalApiKey());
      httpPut.setEntity(new StringEntity(objectMapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));

      // Execute request
      try (CloseableHttpResponse response = httpClient.execute(httpPut)) {
        int statusCode = response.getCode();

        if (statusCode >= 200 && statusCode < 300) {
          logger.info("✅ Successfully updated OTP for player: {}", mcid);
        } else {
          String responseBody = new String(response.getEntity().getContent().readAllBytes());
          logger.error("❌ Failed to update OTP. Status: {}, Response: {}", statusCode, responseBody);
          throw new RuntimeException("Failed to update OTP: HTTP " + statusCode);
        }
      }

    } catch (Exception e) {
      logger.error("❌ Error updating player OTP: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to update player OTP", e);
    }
  }

  /**
   * Confirm MinecraftPlayer authentication via Web API
   */
  public void confirmPlayerAuthentication(String mcid, String uuid) {
    try {
      String endpoint = webApiBaseUrl + "/api/internal/minecraft-player/confirm";

      // Create request payload
      ObjectNode payload = objectMapper.createObjectNode();
      payload.put("mcid", mcid);
      payload.put("uuid", uuid);
      payload.put("confirmed", true);

      // Create HTTP request
      HttpPut httpPut = new HttpPut(endpoint);
      httpPut.setHeader("Content-Type", "application/json");
      httpPut.setHeader("Authorization", "Bearer " + getInternalApiKey());
      httpPut.setEntity(new StringEntity(objectMapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));

      // Execute request
      try (CloseableHttpResponse response = httpClient.execute(httpPut)) {
        int statusCode = response.getCode();

        if (statusCode >= 200 && statusCode < 300) {
          logger.info("✅ Successfully confirmed authentication for player: {} ({})", mcid, uuid);
        } else {
          String responseBody = new String(response.getEntity().getContent().readAllBytes());
          logger.error("❌ Failed to confirm authentication. Status: {}, Response: {}", statusCode, responseBody);
          throw new RuntimeException("Failed to confirm authentication: HTTP " + statusCode);
        }
      }

    } catch (Exception e) {
      logger.error("❌ Error confirming player authentication: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to confirm player authentication", e);
    }
  }

  /**
   * Get MinecraftPlayer by MCID via Web API
   */
  public MinecraftPlayer getPlayerByMcid(String mcid) {
    try {
      String endpoint = webApiBaseUrl + "/api/internal/minecraft-player/" + mcid;

      // Create HTTP request
      org.apache.hc.client5.http.classic.methods.HttpGet httpGet = new org.apache.hc.client5.http.classic.methods.HttpGet(
          endpoint);
      httpGet.setHeader("Authorization", "Bearer " + getInternalApiKey());

      // Execute request
      try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
        int statusCode = response.getCode();
        String responseBody = new String(response.getEntity().getContent().readAllBytes());

        if (statusCode >= 200 && statusCode < 300) {
          JsonNode responseJson = objectMapper.readTree(responseBody);
          JsonNode playerData = responseJson.path("data");

          if (playerData.isNull()) {
            return null;
          }

          return new MinecraftPlayer(
              playerData.path("id").asLong(),
              playerData.path("mcid").asText(),
              playerData.path("uuid").asText(),
              playerData.path("authToken").asText(),
              playerData.path("tokenExpires").isNull() ? null
                  : Instant.ofEpochMilli(playerData.path("tokenExpires").asLong()),
              playerData.path("otp").asText(null),
              playerData.path("otpExpires").isNull() ? null
                  : Instant.ofEpochMilli(playerData.path("otpExpires").asLong()),
              playerData.path("confirmed").asBoolean());
        } else if (statusCode == 404) {
          return null; // Player not found
        } else {
          logger.error("❌ Failed to get player. Status: {}, Response: {}", statusCode, responseBody);
          throw new RuntimeException("Failed to get player: HTTP " + statusCode);
        }
      }

    } catch (Exception e) {
      logger.error("❌ Error getting player by MCID: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to get player by MCID", e);
    }
  }

  /**
   * Get internal API key for authentication
   * In production, this should be loaded from environment variables or secure
   * configuration
   */
  private String getInternalApiKey() {
    // TODO: Implement proper API key management
    return System.getenv("INTERNAL_API_KEY");
  }

  /**
   * Close the database client
   */
  public void close() {
    try {
      if (httpClient != null) {
        httpClient.close();
      }
      logger.info("📴 Database client closed");
    } catch (Exception e) {
      logger.error("❌ Error closing database client: {}", e.getMessage(), e);
    }
  }

  /**
   * MinecraftPlayer data class
   */
  public static class MinecraftPlayer {
    public final long id;
    public final String mcid;
    public final String uuid;
    public final String authToken;
    public final Instant tokenExpires;
    public final String otp;
    public final Instant otpExpires;
    public final boolean confirmed;

    public MinecraftPlayer(long id, String mcid, String uuid, String authToken,
        Instant tokenExpires, String otp, Instant otpExpires, boolean confirmed) {
      this.id = id;
      this.mcid = mcid;
      this.uuid = uuid;
      this.authToken = authToken;
      this.tokenExpires = tokenExpires;
      this.otp = otp;
      this.otpExpires = otpExpires;
      this.confirmed = confirmed;
    }

    @Override
    public String toString() {
      return String.format("MinecraftPlayer{id=%d, mcid='%s', uuid='%s', confirmed=%s}",
          id, mcid, uuid, confirmed);
    }
  }
}
