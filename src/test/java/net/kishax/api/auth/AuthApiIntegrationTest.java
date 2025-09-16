package net.kishax.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthApiController の統合テスト
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AuthApiIntegrationTest {

  private Javalin app;
  private HttpClient httpClient;
  private ObjectMapper objectMapper;
  private final String baseUrl = "http://localhost:8081";
  private final String testApiKey = "test-api-key-12345";

  @Mock
  private DatabaseService mockDatabaseService;

  @BeforeEach
  void setUp() throws Exception {
    MockitoAnnotations.openMocks(this);

    // ObjectMapper設定
    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());

    // テスト用Javalinアプリ起動
    AuthApiController controller = new AuthApiController(mockDatabaseService, objectMapper, testApiKey);
    app = Javalin.create(config -> {
      config.showJavalinBanner = false;
    }).start(8081);

    controller.setupRoutes(app);

    // HttpClient作成
    httpClient = HttpClient.newBuilder().build();

    System.out.println("Test server started on " + baseUrl);
  }

  @AfterEach
  void tearDown() {
    if (app != null) {
      app.stop();
    }
  }

  @Test
  void testHealthEndpoint() throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/api/health"))
        .GET()
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("OK"));
  }

  @Test
  void testAuthCheckWithValidApiKey() throws Exception {
    // Mock DatabaseService response
    DatabaseService.AuthCheckResult mockResult = new DatabaseService.AuthCheckResult(
        AuthLevel.MC_AUTHENTICATED_LINKED,
        List.of(),
        "user123");
    when(mockDatabaseService.getAuthLevel(anyString(), anyString())).thenReturn(mockResult);

    // Request body
    AuthCheckRequest requestBody = new AuthCheckRequest("testPlayer", "test-uuid-123");
    String jsonBody = objectMapper.writeValueAsString(requestBody);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/api/auth/check-permission"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + testApiKey)
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());

    AuthLevelResponse authResponse = objectMapper.readValue(response.body(), AuthLevelResponse.class);
    assertEquals(AuthLevel.MC_AUTHENTICATED_LINKED, authResponse.getAuthLevel());
    assertEquals("user123", authResponse.getKishaxUserId());
    assertTrue(authResponse.getActiveProducts().isEmpty());

    // Verify database service was called
    verify(mockDatabaseService).getAuthLevel("testPlayer", "test-uuid-123");
  }

  @Test
  void testAuthCheckWithProductPurchased() throws Exception {
    // Mock DatabaseService response with products
    DatabaseService.AuthCheckResult mockResult = new DatabaseService.AuthCheckResult(
        AuthLevel.MC_AUTHENTICATED_PRODUCT,
        List.of("Premium Access", "VIP Package"),
        "user456");
    when(mockDatabaseService.getAuthLevel(anyString(), anyString())).thenReturn(mockResult);

    // Request body
    AuthCheckRequest requestBody = new AuthCheckRequest("premiumPlayer", "premium-uuid-456");
    String jsonBody = objectMapper.writeValueAsString(requestBody);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/api/auth/check-permission"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + testApiKey)
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());

    AuthLevelResponse authResponse = objectMapper.readValue(response.body(), AuthLevelResponse.class);
    assertEquals(AuthLevel.MC_AUTHENTICATED_PRODUCT, authResponse.getAuthLevel());
    assertEquals("user456", authResponse.getKishaxUserId());
    assertEquals(2, authResponse.getActiveProducts().size());
    assertTrue(authResponse.getActiveProducts().contains("Premium Access"));
    assertTrue(authResponse.getActiveProducts().contains("VIP Package"));

    verify(mockDatabaseService).getAuthLevel("premiumPlayer", "premium-uuid-456");
  }

  @Test
  void testAuthCheckWithInvalidApiKey() throws Exception {
    AuthCheckRequest requestBody = new AuthCheckRequest("testPlayer", "test-uuid-123");
    String jsonBody = objectMapper.writeValueAsString(requestBody);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/api/auth/check-permission"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer invalid-api-key")
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(401, response.statusCode());
    assertTrue(response.body().contains("Invalid API key"));

    // Verify database service was NOT called
    verify(mockDatabaseService, never()).getAuthLevel(anyString(), anyString());
  }

  @Test
  void testAuthCheckWithMissingParameters() throws Exception {
    // Request with missing uuid
    AuthCheckRequest requestBody = new AuthCheckRequest("testPlayer", null);
    String jsonBody = objectMapper.writeValueAsString(requestBody);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/api/auth/check-permission"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + testApiKey)
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("mcid and uuid are required"));

    verify(mockDatabaseService, never()).getAuthLevel(anyString(), anyString());
  }

  @Test
  void testAuthCheckWithDatabaseError() throws Exception {
    // Mock DatabaseService to throw exception
    when(mockDatabaseService.getAuthLevel(anyString(), anyString()))
        .thenThrow(new RuntimeException("Database connection failed"));

    AuthCheckRequest requestBody = new AuthCheckRequest("testPlayer", "test-uuid-123");
    String jsonBody = objectMapper.writeValueAsString(requestBody);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/api/auth/check-permission"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + testApiKey)
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(500, response.statusCode());
    assertTrue(response.body().contains("Internal server error"));

    verify(mockDatabaseService).getAuthLevel("testPlayer", "test-uuid-123");
  }

  @Test
  void testUnauthenticatedPlayer() throws Exception {
    // Mock DatabaseService response for unauthenticated player
    DatabaseService.AuthCheckResult mockResult = new DatabaseService.AuthCheckResult(
        AuthLevel.MC_UNAUTHENTICATED,
        List.of(),
        null);
    when(mockDatabaseService.getAuthLevel(anyString(), anyString())).thenReturn(mockResult);

    AuthCheckRequest requestBody = new AuthCheckRequest("newPlayer", "new-uuid-789");
    String jsonBody = objectMapper.writeValueAsString(requestBody);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/api/auth/check-permission"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + testApiKey)
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());

    AuthLevelResponse authResponse = objectMapper.readValue(response.body(), AuthLevelResponse.class);
    assertEquals(AuthLevel.MC_UNAUTHENTICATED, authResponse.getAuthLevel());
    assertNull(authResponse.getKishaxUserId());
    assertTrue(authResponse.getActiveProducts().isEmpty());

    verify(mockDatabaseService).getAuthLevel("newPlayer", "new-uuid-789");
  }
}
