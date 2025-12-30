package net.kishax.discord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Discord メッセージID管理クラス
 * プレイヤーのメッセージIDとチャットメッセージIDを管理
 */
public class MessageIdManager {
  private static final Logger logger = LoggerFactory.getLogger(MessageIdManager.class);

  // プレイヤーUUID -> メッセージ情報（ID + 内容）
  private final ConcurrentHashMap<String, PlayerMessageInfo> playerMessages = new ConcurrentHashMap<>();

  // チャットメッセージ用のID（1つだけ保持）
  private volatile String chatMessageId = null;

  /**
   * プレイヤーメッセージ情報を保持する内部クラス
   */
  public static class PlayerMessageInfo {
    private final String messageId;
    private String content;

    public PlayerMessageInfo(String messageId, String content) {
      this.messageId = messageId;
      this.content = content;
    }

    public String getMessageId() {
      return messageId;
    }

    public String getContent() {
      return content;
    }

    public void setContent(String content) {
      this.content = content;
    }
  }

  /**
   * プレイヤーのメッセージIDを保存（後方互換性のため）
   */
  public void putPlayerMessageId(String uuid, String messageId) {
    if (uuid != null && messageId != null) {
      PlayerMessageInfo info = playerMessages.get(uuid);
      if (info == null) {
        playerMessages.put(uuid, new PlayerMessageInfo(messageId, ""));
      }
      logger.debug("Player message id is saved: {} -> {}", uuid, messageId);
    }
  }

  /**
   * プレイヤーのメッセージIDと内容を保存
   */
  public void putPlayerMessage(String uuid, String messageId, String content) {
    if (uuid != null && messageId != null) {
      playerMessages.put(uuid, new PlayerMessageInfo(messageId, content != null ? content : ""));
      logger.debug("Player message is saved: {} -> {} (content length: {})", uuid, messageId, content != null ? content.length() : 0);
    }
  }

  /**
   * プレイヤーのメッセージIDを取得
   */
  public String getPlayerMessageId(String uuid) {
    if (uuid == null) {
      return null;
    }
    PlayerMessageInfo info = playerMessages.get(uuid);
    return info != null ? info.getMessageId() : null;
  }

  /**
   * プレイヤーのメッセージ内容を取得
   */
  public String getPlayerMessageContent(String uuid) {
    if (uuid == null) {
      return null;
    }
    PlayerMessageInfo info = playerMessages.get(uuid);
    return info != null ? info.getContent() : null;
  }

  /**
   * プレイヤーのメッセージ内容を更新
   */
  public void updatePlayerMessageContent(String uuid, String content) {
    if (uuid != null) {
      PlayerMessageInfo info = playerMessages.get(uuid);
      if (info != null) {
        info.setContent(content);
        logger.debug("Updated player message content: {} (length: {})", uuid, content != null ? content.length() : 0);
      }
    }
  }

  /**
   * プレイヤーのメッセージIDを削除
   */
  public String removePlayerMessageId(String uuid) {
    if (uuid == null) {
      return null;
    }
    PlayerMessageInfo info = playerMessages.remove(uuid);
    if (info != null) {
      logger.debug("Deleted player message id: {} -> {}", uuid, info.getMessageId());
      return info.getMessageId();
    }
    return null;
  }

  /**
   * チャットメッセージIDを設定
   */
  public void setChatMessageId(String messageId) {
    this.chatMessageId = messageId;
    logger.debug("Set chat message id: {}", messageId);
  }

  /**
   * チャットメッセージIDを取得
   */
  public String getChatMessageId() {
    return chatMessageId;
  }

  /**
   * チャットメッセージIDをクリア
   */
  public void clearChatMessageId() {
    logger.debug("Be clear chat message id: {}", chatMessageId);
    this.chatMessageId = null;
  }

  /**
   * 全データをクリア
   */
  public void clear() {
    playerMessages.clear();
    chatMessageId = null;
    logger.info("All message ids are being clear");
  }

  /**
   * 現在の状態を取得（デバッグ用）
   */
  public String getStatus() {
    return String.format("PlayerMessages: %d, ChatMessageId: %s",
        playerMessages.size(), chatMessageId);
  }
}
