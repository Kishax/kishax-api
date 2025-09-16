package net.kishax.aws.auth;

import java.util.List;

/**
 * MC認証レベルを表すenum
 */
public enum AuthLevel {
  MC_UNAUTHENTICATED("a", "MC未認証"),
  MC_AUTHENTICATED_TEMP("b", "MC認証クリア（一時権限）"),
  MC_AUTHENTICATED_UNLINKED("c", "MC認証クリア＋Kishaxアカウント未連携"),
  MC_AUTHENTICATED_LINKED("d", "MC認証クリア＋Kishaxアカウント連携済み"),
  MC_AUTHENTICATED_PRODUCT("e", "MC認証クリア＋プロダクト購入済み");

  private final String code;
  private final String description;

  AuthLevel(String code, String description) {
    this.code = code;
    this.description = description;
  }

  public String getCode() {
    return code;
  }

  public String getDescription() {
    return description;
  }

  public static AuthLevel fromCode(String code) {
    for (AuthLevel level : values()) {
      if (level.code.equals(code)) {
        return level;
      }
    }
    throw new IllegalArgumentException("Unknown auth level code: " + code);
  }
}
