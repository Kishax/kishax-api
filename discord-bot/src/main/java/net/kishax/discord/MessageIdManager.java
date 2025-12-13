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

  // プレイヤーUUID -> メッセージID
  private final ConcurrentHashMap<String, String> playerMessageIds = new ConcurrentHashMap<>();

  // チャットメッセージ用のID（1つだけ保持）
  private volatile String chatMessageId = null;

  /**
   * プレイヤーのメッセージIDを保存
   */
  public void putPlayerMessageId(String uuid, String messageId) {
    if (uuid != null && messageId != null) {
      playerMessageIds.put(uuid, messageId);
      logger.debug("Player message id is saved: {} -> {}", uuid, messageId);
    }
  }

  /**
   * プレイヤーのメッセージIDを取得
   */
  public String getPlayerMessageId(String uuid) {
    if (uuid == null) {
      return null;
    }
    return playerMessageIds.get(uuid);
  }

  /**
   * プレイヤーのメッセージIDを削除
   */
  public String removePlayerMessageId(String uuid) {
    if (uuid == null) {
      return null;
    }
    String messageId = playerMessageIds.remove(uuid);
    if (messageId != null) {
      logger.debug("Deleted player message id: {} -> {}", uuid, messageId);
    }
    return messageId;
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
    playerMessageIds.clear();
    chatMessageId = null;
    logger.info("All message ids are being clear");
  }

  /**
   * 現在の状態を取得（デバッグ用）
   */
  public String getStatus() {
    return String.format("PlayerMessages: %d, ChatMessageId: %s",
        playerMessageIds.size(), chatMessageId);
  }
}
