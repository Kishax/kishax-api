package net.kishax.api.auth;

/**
 * MC認証APIクライアント設定
 */
public class McAuthClientConfig {
  private final String apiUrl;
  private final String apiKey;

  public McAuthClientConfig(String apiUrl, String apiKey) {
    if (apiUrl == null || apiUrl.trim().isEmpty()) {
      throw new IllegalArgumentException("API URL cannot be null or empty");
    }
    if (apiKey == null || apiKey.trim().isEmpty()) {
      throw new IllegalArgumentException("API Key cannot be null or empty");
    }

    this.apiUrl = apiUrl;
    this.apiKey = apiKey;
  }

  public String getApiUrl() {
    return apiUrl;
  }

  public String getApiKey() {
    return apiKey;
  }

  /**
   * 環境変数からクライアント設定を作成
   *
   * @return 設定インスタンス
   * @throws IllegalStateException 必要な環境変数が設定されていない場合
   */
  public static McAuthClientConfig fromEnvironment() {
    String apiUrl = System.getenv("AUTH_API_URL");
    String apiKey = System.getenv("AUTH_API_KEY");

    if (apiUrl == null || apiUrl.trim().isEmpty()) {
      throw new IllegalStateException("AUTH_API_URL environment variable is required");
    }
    if (apiKey == null || apiKey.trim().isEmpty()) {
      throw new IllegalStateException("AUTH_API_KEY environment variable is required");
    }

    return new McAuthClientConfig(apiUrl, apiKey);
  }

  /**
   * システムプロパティからクライアント設定を作成
   *
   * @return 設定インスタンス
   * @throws IllegalStateException 必要なシステムプロパティが設定されていない場合
   */
  public static McAuthClientConfig fromSystemProperties() {
    String apiUrl = System.getProperty("auth.api.url");
    String apiKey = System.getProperty("auth.api.key");

    if (apiUrl == null || apiUrl.trim().isEmpty()) {
      throw new IllegalStateException("mcauth.api.url system property is required");
    }
    if (apiKey == null || apiKey.trim().isEmpty()) {
      throw new IllegalStateException("mcauth.api.key system property is required");
    }

    return new McAuthClientConfig(apiUrl, apiKey);
  }

  /**
   * 指定されたパラメータからクライアント設定を作成
   *
   * @param apiUrl API URL
   * @param apiKey API キー
   * @return 設定インスタンス
   * @throws IllegalArgumentException パラメータが無効な場合
   */
  public static McAuthClientConfig fromParameters(String apiUrl, String apiKey) {
    return new McAuthClientConfig(apiUrl, apiKey);
  }

  @Override
  public String toString() {
    return "McAuthClientConfig{" +
        "apiUrl='" + apiUrl + '\'' +
        ", apiKey='***'" +
        '}';
  }
}
