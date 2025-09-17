package net.kishax.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * McAuthClient のユニットテスト
 */
class McAuthClientTest {

  private MockWebServer mockServer;
  private McAuthClient client;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() throws IOException {
    mockServer = new MockWebServer();
    mockServer.start();

    String baseUrl = mockServer.url("/").toString();
    client = new McAuthClient(baseUrl, "test-api-key");
    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
  }

  @AfterEach
  void tearDown() throws IOException {
    client.close();
    mockServer.shutdown();
  }

  @Test
  void checkPermission_成功ケース() throws Exception {
    // レスポンスデータの準備
    AuthLevelResponse expectedResponse = new AuthLevelResponse(
        AuthLevel.MC_AUTHENTICATED_PRODUCT,
        List.of("product1", "product2"),
        "user123");

    String responseJson = objectMapper.writeValueAsString(expectedResponse);

    mockServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(responseJson));

    // テスト実行
    AuthLevelResponse result = client.checkPermission("testplayer", "550e8400-e29b-41d4-a716-446655440000");

    // 検証
    assertNotNull(result);
    assertEquals(AuthLevel.MC_AUTHENTICATED_PRODUCT, result.getAuthLevel());
    assertEquals(2, result.getActiveProducts().size());
    assertTrue(result.getActiveProducts().contains("product1"));
    assertTrue(result.getActiveProducts().contains("product2"));
    assertEquals("user123", result.getKishaxUserId());

    // リクエスト内容の検証
    RecordedRequest request = mockServer.takeRequest();
    assertEquals("POST", request.getMethod());
    assertEquals("/api/auth/check-permission", request.getPath());
    assertEquals("Bearer test-api-key", request.getHeader("Authorization"));
    assertTrue(request.getHeader("Content-Type").startsWith("application/json"));

    // リクエストボディの検証
    AuthCheckRequest requestBody = objectMapper.readValue(request.getBody().readUtf8(), AuthCheckRequest.class);
    assertEquals("testplayer", requestBody.getMcid());
    assertEquals("550e8400-e29b-41d4-a716-446655440000", requestBody.getUuid());
  }

  @Test
  void checkPermission_認証エラー() {
    mockServer.enqueue(new MockResponse()
        .setResponseCode(401)
        .setBody("{\"error\":\"Invalid API key\"}"));

    McAuthClient.McAuthException exception = assertThrows(
        McAuthClient.McAuthException.class,
        () -> client.checkPermission("testplayer", "550e8400-e29b-41d4-a716-446655440000"));

    assertTrue(exception.getMessage().contains("HTTP 401"));
  }

  @Test
  void checkPermission_無効なリクエスト() {
    mockServer.enqueue(new MockResponse()
        .setResponseCode(400)
        .setBody("{\"error\":\"mcid and uuid are required\"}"));

    McAuthClient.McAuthException exception = assertThrows(
        McAuthClient.McAuthException.class,
        () -> client.checkPermission("testplayer", "550e8400-e29b-41d4-a716-446655440000"));

    assertTrue(exception.getMessage().contains("HTTP 400"));
  }

  @Test
  void checkPermission_空のパラメータ() {
    assertThrows(IllegalArgumentException.class, () -> client.checkPermission("", "uuid"));
    assertThrows(IllegalArgumentException.class, () -> client.checkPermission(null, "uuid"));
    assertThrows(IllegalArgumentException.class, () -> client.checkPermission("mcid", ""));
    assertThrows(IllegalArgumentException.class, () -> client.checkPermission("mcid", null));
  }

  @Test
  void healthCheck_成功() {
    mockServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .setBody("{\"status\":\"OK\"}"));

    boolean result = client.healthCheck();

    assertTrue(result);
  }

  @Test
  void healthCheck_失敗() {
    mockServer.enqueue(new MockResponse()
        .setResponseCode(500));

    boolean result = client.healthCheck();

    assertFalse(result);
  }
}
