package net.kishax.api.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 認証レベルチェックリクエスト
 */
public class AuthCheckRequest {
  @JsonProperty("mcid")
  private String mcid;

  @JsonProperty("uuid")
  private String uuid;

  public AuthCheckRequest() {
  }

  public AuthCheckRequest(String mcid, String uuid) {
    this.mcid = mcid;
    this.uuid = uuid;
  }

  public String getMcid() {
    return mcid;
  }

  public void setMcid(String mcid) {
    this.mcid = mcid;
  }

  public String getUuid() {
    return uuid;
  }

  public void setUuid(String uuid) {
    this.uuid = uuid;
  }

  @Override
  public String toString() {
    return "AuthCheckRequest{" +
        "mcid='" + mcid + '\'' +
        ", uuid='" + uuid + '\'' +
        '}';
  }
}
