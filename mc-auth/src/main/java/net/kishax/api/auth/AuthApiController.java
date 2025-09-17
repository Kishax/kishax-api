package net.kishax.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 認証API コントローラー
 * MC側からの権限チェックリクエストを処理
 */
public class AuthApiController {
  private static final Logger logger = LoggerFactory.getLogger(AuthApiController.class);

  private final DatabaseService databaseService;
  private final ObjectMapper objectMapper;
  private final String apiKey;

  public AuthApiController(DatabaseService databaseService, ObjectMapper objectMapper, String apiKey) {
    this.databaseService = databaseService;
    this.objectMapper = objectMapper;
    this.apiKey = apiKey;
  }

  /**
   * JavalinアプリにルートをセットアップU
   */
  public void setupRoutes(Javalin app) {
    // CORS設定
    app.before(ctx -> {
      ctx.header("Access-Control-Allow-Origin", "*");
      ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
      ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    });

    // OPTIONS リクエスト対応（プリフライト）
    app.options("/api/auth/*", ctx -> ctx.status(HttpStatus.OK));

    // メイン認証チェックエンドポイント
    app.post("/api/auth/check-permission", this::checkPermission);

    // ヘルスチェックエンドポイント
    app.get("/api/health", ctx -> {
      ctx.json(new HealthResponse("OK", System.currentTimeMillis()));
    });

    logger.info("Auth API routes configured");
  }

  /**
   * 権限チェックエンドポイント
   */
  private void checkPermission(Context ctx) {
    try {
      // API Key認証
      if (!isValidApiKey(ctx)) {
        ctx.status(HttpStatus.UNAUTHORIZED);
        ctx.json(new ErrorResponse("Invalid API key"));
        logger.warn("Invalid API key from {}", ctx.ip());
        return;
      }

      // リクエストボディをパース
      AuthCheckRequest request = objectMapper.readValue(ctx.body(), AuthCheckRequest.class);

      // 入力検証
      if (request.getMcid() == null || request.getUuid() == null) {
        ctx.status(HttpStatus.BAD_REQUEST);
        ctx.json(new ErrorResponse("mcid and uuid are required"));
        return;
      }

      logger.debug("Processing auth check for mcid={}, uuid={}", request.getMcid(), request.getUuid());

      // データベースクエリ実行
      DatabaseService.AuthCheckResult result = databaseService.getAuthLevel(request.getMcid(), request.getUuid());

      // レスポンス作成
      AuthLevelResponse response = new AuthLevelResponse(
          result.getAuthLevel(),
          result.getActiveProducts(),
          result.getKishaxUserId());

      ctx.status(HttpStatus.OK);
      ctx.json(response);

      logger.info("Auth check completed: mcid={}, uuid={}, level={}, products={}",
          request.getMcid(), request.getUuid(), result.getAuthLevel(), result.getActiveProducts().size());

    } catch (Exception e) {
      logger.error("Error processing auth check request", e);
      ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
      ctx.json(new ErrorResponse("Internal server error"));
    }
  }

  /**
   * API Key 検証
   */
  private boolean isValidApiKey(Context ctx) {
    String authHeader = ctx.header("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      return false;
    }

    String providedApiKey = authHeader.substring(7); // "Bearer " を除去
    return apiKey != null && apiKey.equals(providedApiKey);
  }

  /**
   * エラーレスポンス用クラス
   */
  public static class ErrorResponse {
    private final String error;
    private final long timestamp;

    public ErrorResponse(String error) {
      this.error = error;
      this.timestamp = System.currentTimeMillis();
    }

    public String getError() {
      return error;
    }

    public long getTimestamp() {
      return timestamp;
    }
  }

  /**
   * ヘルスチェックレスポンス用クラス
   */
  public static class HealthResponse {
    private final String status;
    private final long timestamp;

    public HealthResponse(String status, long timestamp) {
      this.status = status;
      this.timestamp = timestamp;
    }

    public String getStatus() {
      return status;
    }

    public long getTimestamp() {
      return timestamp;
    }
  }
}
