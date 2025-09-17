package net.kishax.api.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * 認証レベルチェックレスポンス
 */
public class AuthLevelResponse {
  @JsonProperty("authLevel")
  private AuthLevel authLevel;

  @JsonProperty("activeProducts")
  private List<String> activeProducts;

  @JsonProperty("kishaxUserId")
  private String kishaxUserId;

  @JsonProperty("lastUpdated")
  private Instant lastUpdated;

  public AuthLevelResponse() {
  }

  public AuthLevelResponse(AuthLevel authLevel, List<String> activeProducts) {
    this.authLevel = authLevel;
    this.activeProducts = activeProducts;
    this.lastUpdated = Instant.now();
  }

  public AuthLevelResponse(AuthLevel authLevel, List<String> activeProducts, String kishaxUserId) {
    this.authLevel = authLevel;
    this.activeProducts = activeProducts;
    this.kishaxUserId = kishaxUserId;
    this.lastUpdated = Instant.now();
  }

  public AuthLevel getAuthLevel() {
    return authLevel;
  }

  public void setAuthLevel(AuthLevel authLevel) {
    this.authLevel = authLevel;
  }

  public List<String> getActiveProducts() {
    return activeProducts;
  }

  public void setActiveProducts(List<String> activeProducts) {
    this.activeProducts = activeProducts;
  }

  public String getKishaxUserId() {
    return kishaxUserId;
  }

  public void setKishaxUserId(String kishaxUserId) {
    this.kishaxUserId = kishaxUserId;
  }

  public Instant getLastUpdated() {
    return lastUpdated;
  }

  public void setLastUpdated(Instant lastUpdated) {
    this.lastUpdated = lastUpdated;
  }

  @Override
  public String toString() {
    return "AuthLevelResponse{" +
        "authLevel=" + authLevel +
        ", activeProducts=" + activeProducts +
        ", kishaxUserId='" + kishaxUserId + '\'' +
        ", lastUpdated=" + lastUpdated +
        '}';
  }
}
