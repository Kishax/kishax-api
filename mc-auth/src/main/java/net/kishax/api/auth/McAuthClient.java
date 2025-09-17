package net.kishax.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * MC認証APIクライアント
 * MC側からmc-auth APIサーバーへのリクエストを行う
 */
public class McAuthClient {
  private static final Logger logger = LoggerFactory.getLogger(McAuthClient.class);
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

  private final String apiUrl;
  private final String apiKey;
  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;

  public McAuthClient(String apiUrl, String apiKey) {
    this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
    this.apiKey = apiKey;
    this.objectMapper = new ObjectMapper();
    this.objectMapper.registerModule(new JavaTimeModule());
    this.httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofSeconds(30))
        .writeTimeout(Duration.ofSeconds(30))
        .build();

    logger.info("McAuthClient initialized with API URL: {}", this.apiUrl);
  }

  /**
   * 権限チェックを実行
   *
   * @param mcid MinecraftプレイヤーID
   * @param uuid プレイヤーUUID
   * @return 認証レベルレスポンス
   * @throws McAuthException API呼び出しでエラーが発生した場合
   */
  public AuthLevelResponse checkPermission(String mcid, String uuid) throws McAuthException {
    if (mcid == null || mcid.trim().isEmpty()) {
      throw new IllegalArgumentException("mcid cannot be null or empty");
    }
    if (uuid == null || uuid.trim().isEmpty()) {
      throw new IllegalArgumentException("uuid cannot be null or empty");
    }

    AuthCheckRequest request = new AuthCheckRequest(mcid, uuid);

    try {
      String jsonBody = objectMapper.writeValueAsString(request);
      RequestBody body = RequestBody.create(jsonBody, JSON);

      Request httpRequest = new Request.Builder()
          .url(apiUrl + "/api/auth/check-permission")
          .post(body)
          .addHeader("Authorization", "Bearer " + apiKey)
          .addHeader("Content-Type", "application/json")
          .build();

      logger.debug("Sending auth check request: mcid={}, uuid={}", mcid, uuid);

      try (Response response = httpClient.newCall(httpRequest).execute()) {
        String responseBody = response.body() != null ? response.body().string() : "";

        if (response.isSuccessful()) {
          AuthLevelResponse authResponse = objectMapper.readValue(responseBody, AuthLevelResponse.class);
          logger.info("Auth check successful: mcid={}, level={}, products={}",
              mcid, authResponse.getAuthLevel(), authResponse.getActiveProducts().size());
          return authResponse;
        } else {
          logger.error("Auth check failed: HTTP {}, body: {}", response.code(), responseBody);
          throw new McAuthException("Auth check failed: HTTP " + response.code() + " - " + responseBody);
        }
      }
    } catch (IOException e) {
      logger.error("IO error during auth check for mcid={}, uuid={}", mcid, uuid, e);
      throw new McAuthException("IO error during auth check: " + e.getMessage(), e);
    } catch (Exception e) {
      logger.error("Unexpected error during auth check for mcid={}, uuid={}", mcid, uuid, e);
      throw new McAuthException("Unexpected error during auth check: " + e.getMessage(), e);
    }
  }

  /**
   * ヘルスチェックを実行
   *
   * @return APIサーバーが正常な場合true
   */
  public boolean healthCheck() {
    try {
      Request request = new Request.Builder()
          .url(apiUrl + "/api/health")
          .get()
          .build();

      try (Response response = httpClient.newCall(request).execute()) {
        boolean isHealthy = response.isSuccessful();
        logger.debug("Health check result: {}", isHealthy);
        return isHealthy;
      }
    } catch (Exception e) {
      logger.warn("Health check failed", e);
      return false;
    }
  }

  /**
   * リソースをクリーンアップ
   */
  public void close() {
    if (httpClient != null) {
      httpClient.dispatcher().executorService().shutdown();
      httpClient.connectionPool().evictAll();
    }
  }

  /**
   * MC認証API例外
   */
  public static class McAuthException extends Exception {
    public McAuthException(String message) {
      super(message);
    }

    public McAuthException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
