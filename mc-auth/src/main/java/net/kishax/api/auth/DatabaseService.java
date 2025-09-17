package net.kishax.api.auth;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * PostgreSQL データベース接続とクエリ管理
 */
public class DatabaseService {
  private static final Logger logger = LoggerFactory.getLogger(DatabaseService.class);

  private final DataSource dataSource;

  public DatabaseService(String databaseUrl) {
    // Explicitly register PostgreSQL driver
    try {
      Class.forName("org.postgresql.Driver");
      logger.info("PostgreSQL driver loaded successfully");
    } catch (ClassNotFoundException e) {
      logger.error("PostgreSQL driver not found", e);
      throw new RuntimeException("PostgreSQL driver not found", e);
    }

    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(databaseUrl);
    config.setDriverClassName("org.postgresql.Driver");
    config.setMaximumPoolSize(10);
    config.setMinimumIdle(2);
    config.setConnectionTimeout(30000);
    config.setIdleTimeout(600000);
    config.setMaxLifetime(1800000);

    this.dataSource = new HikariDataSource(config);
    logger.info("Database connection pool initialized");
  }

  /**
   * MC情報（mcid, uuid）から認証レベルとアクティブプロダクトを取得
   */
  public AuthCheckResult getAuthLevel(String mcid, String uuid) {
    String query = """
        SELECT
            mp.confirmed,
            mp.kishax_user_id,
            u.id as user_id,
            u.username,
            COALESCE(array_agg(p.name) FILTER (WHERE up.status = 'ACTIVE'
                AND (up.expires_at IS NULL OR up.expires_at > NOW())), '{}') as active_products
        FROM minecraft_players mp
        LEFT JOIN users u ON mp.kishax_user_id = u.id
        LEFT JOIN user_products up ON u.id = up.user_id AND up.status = 'ACTIVE'
            AND (up.expires_at IS NULL OR up.expires_at > NOW())
        LEFT JOIN products p ON up.product_id = p.id AND p.status = 'ACTIVE'
        WHERE mp.mcid = ? OR mp.uuid = ?
        GROUP BY mp.confirmed, mp.kishax_user_id, u.id, u.username
        """;

    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(query)) {

      stmt.setString(1, mcid);
      stmt.setString(2, uuid);

      try (ResultSet rs = stmt.executeQuery()) {
        if (rs.next()) {
          boolean confirmed = rs.getBoolean("confirmed");
          String kishaxUserId = rs.getString("kishax_user_id");
          String[] productArray = (String[]) rs.getArray("active_products").getArray();
          List<String> activeProducts = List.of(productArray);

          // 認証レベル判定
          AuthLevel authLevel = determineAuthLevel(confirmed, kishaxUserId, activeProducts);

          return new AuthCheckResult(authLevel, activeProducts, kishaxUserId);
        } else {
          // プレイヤーが見つからない場合 = 未認証
          return new AuthCheckResult(AuthLevel.MC_UNAUTHENTICATED, List.of(), null);
        }
      }
    } catch (SQLException e) {
      logger.error("Database query failed for mcid={}, uuid={}", mcid, uuid, e);
      throw new RuntimeException("Database query failed", e);
    }
  }

  /**
   * 認証レベル判定ロジック
   */
  private AuthLevel determineAuthLevel(boolean confirmed, String kishaxUserId, List<String> activeProducts) {
    if (!confirmed) {
      return AuthLevel.MC_AUTHENTICATED_TRYING;
    }

    if (kishaxUserId == null) {
      return AuthLevel.MC_AUTHENTICATED_UNLINKED;
    }

    if (activeProducts.isEmpty()) {
      return AuthLevel.MC_AUTHENTICATED_LINKED;
    }

    return AuthLevel.MC_AUTHENTICATED_PRODUCT;
  }

  /**
   * 認証チェック結果を格納するクラス
   */
  public static class AuthCheckResult {
    private final AuthLevel authLevel;
    private final List<String> activeProducts;
    private final String kishaxUserId;

    public AuthCheckResult(AuthLevel authLevel, List<String> activeProducts, String kishaxUserId) {
      this.authLevel = authLevel;
      this.activeProducts = activeProducts;
      this.kishaxUserId = kishaxUserId;
    }

    public AuthLevel getAuthLevel() {
      return authLevel;
    }

    public List<String> getActiveProducts() {
      return activeProducts;
    }

    public String getKishaxUserId() {
      return kishaxUserId;
    }
  }

  public void close() {
    if (dataSource instanceof HikariDataSource) {
      ((HikariDataSource) dataSource).close();
      logger.info("Database connection pool closed");
    }
  }
}
