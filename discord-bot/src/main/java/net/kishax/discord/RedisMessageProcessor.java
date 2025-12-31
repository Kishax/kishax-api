package net.kishax.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.kishax.api.common.Configuration;
import net.kishax.discord.ColorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Redisメッセージプロセッサー
 * SQS直接受信からRedis経由受信に変更
 */
public class RedisMessageProcessor {
  private static final Logger logger = LoggerFactory.getLogger(RedisMessageProcessor.class);
  private static final String DISCORD_REQUESTS_CHANNEL = "discord_requests";
  private static final String DISCORD_RESPONSES_CHANNEL = "discord_responses";
  private static final int DISCORD_EMBED_MAX_LENGTH = 4096;

  private final JedisPool jedisPool;
  private final JDA jda;
  private final Configuration config;
  private final EmojiManager emojiManager;
  private final MessageIdManager messageIdManager;
  private final ObjectMapper objectMapper;
  private final ExecutorService executorService;
  private volatile boolean running = false;

  public RedisMessageProcessor(String redisUrl, JDA jda, Configuration config,
                              EmojiManager emojiManager, MessageIdManager messageIdManager) {
    this.jedisPool = new JedisPool(redisUrl);
    this.jda = jda;
    this.config = config;
    this.emojiManager = emojiManager;
    this.messageIdManager = messageIdManager;
    this.objectMapper = new ObjectMapper();
    this.executorService = Executors.newFixedThreadPool(2);
  }

  /**
   * Redisメッセージ購読開始
   */
  public void start() {
    if (running) {
      logger.warn("RedisMessageProcessor is already running");
      return;
    }

    running = true;
    logger.info("Starting the processer of redis message...");

    // Subscribe to discord requests
    CompletableFuture.runAsync(() -> {
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.subscribe(new JedisPubSub() {
          @Override
          public void onMessage(String channel, String message) {
            if (DISCORD_REQUESTS_CHANNEL.equals(channel)) {
              handleRedisMessage(message);
            }
          }

          @Override
          public void onSubscribe(String channel, int subscribedChannels) {
            logger.info("Subscribed the redis channel: {}", channel);
          }

          @Override
          public void onUnsubscribe(String channel, int subscribedChannels) {
            logger.info("Canceld the publishing redis chennel: {}", channel);
          }
        }, DISCORD_REQUESTS_CHANNEL);
      } catch (Exception e) {
        logger.error("An error occurred while subscribeing redis channel", e);
      }
    }, executorService);
  }

  /**
   * Redisメッセージ処理停止
   */
  public void stop() {
    if (!running) {
      return;
    }

    running = false;
    logger.info("Stopping the processer of redis message...");

    if (executorService != null && !executorService.isShutdown()) {
      executorService.shutdown();
    }

    if (jedisPool != null && !jedisPool.isClosed()) {
      jedisPool.close();
    }
  }

  /**
   * Redisから受信したメッセージの処理
   */
  private void handleRedisMessage(String messageJson) {
    try {
      logger.debug("📜 Row response json: {}", messageJson);

      JsonNode requestNode = objectMapper.readTree(messageJson);
      if (requestNode.isTextual()) {
        requestNode = objectMapper.readTree(requestNode.asText());
      }

      // 詳細デバッグ: フィールド取得の問題を調査
      JsonNode typeNode = requestNode.path("type");
      JsonNode actionNode = requestNode.path("action");
      // JsonNode sourceNode = requestNode.path("source");
      JsonNode dataNode = requestNode.path("data");

      String type = typeNode.asText();
      String action = actionNode.asText();
      JsonNode data = dataNode;

      if ("discord_action".equals(type)) {
        logger.debug("🎮 Starting process of discord message for action: {}", action);

        final JsonNode finalRequestNode = requestNode;
        CompletableFuture.runAsync(() -> {
          try {
            // 直接Discord処理を実行
            processMessage(objectMapper.writeValueAsString(data));

            // 成功応答を送信
            sendSuccessResponse(action, finalRequestNode.path("source").asText());
          } catch (Exception e) {
            logger.error("An error occurred while processing discord: {}", action, e);

            // エラー応答を送信
            sendErrorResponse(action, e.getMessage(), finalRequestNode.path("source").asText());
          }
        }, executorService);
      } else {
        logger.warn("⚠️ Unsupported message type: type={}, action={}", type, action);
      }

    } catch (Exception e) {
      logger.error("An error occurred while processing redis message", e);
    }
  }

  /**
   * 成功応答をRedisで送信
   */
  private void sendSuccessResponse(String originalAction, String source) {
    try {
      Map<String, Object> response = new HashMap<>();
      response.put("type", "discord_response");
      response.put("result", "success");
      response.put("action", originalAction);
      response.put("timestamp", System.currentTimeMillis());

      Map<String, Object> responseData = new HashMap<>();
      responseData.put("source", source);
      response.put("data", responseData);

      String responseJson = objectMapper.writeValueAsString(response);

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.publish(DISCORD_RESPONSES_CHANNEL, responseJson);
      }
    } catch (Exception e) {
      logger.error("An error occurred while responding the success message", e);
    }
  }

  /**
   * エラー応答をRedisで送信
   */
  private void sendErrorResponse(String originalAction, String errorMessage, String source) {
    try {
      Map<String, Object> response = new HashMap<>();
      response.put("type", "discord_response");
      response.put("result", "error");
      response.put("action", originalAction);
      response.put("error_message", errorMessage);
      response.put("timestamp", System.currentTimeMillis());

      Map<String, Object> responseData = new HashMap<>();
      responseData.put("source", source);
      response.put("data", responseData);

      String responseJson = objectMapper.writeValueAsString(response);

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.publish(DISCORD_RESPONSES_CHANNEL, responseJson);
        logger.warn("Sent the error of discord responding: {} - {}", originalAction, errorMessage);
      }
    } catch (Exception e) {
      logger.error("An error occurred while responding discord error", e);
    }
  }

  /**
   * メッセージ本文からJSON処理（SqsMessageProcessorと同等）
   */
  public void processMessage(String messageBody) throws Exception {
    JsonNode json = objectMapper.readTree(messageBody);
    String messageType = json.path("type").asText();

    switch (messageType) {
      case "server_status" -> processServerStatusMessage(json);
      case "player_request" -> processPlayerRequestMessage(json);
      case "broadcast" -> processBroadcastMessage(json);
      case "embed" -> processEmbedMessage(json);
      case "player_event" -> processPlayerEventMessage(json);
      case "webhook" -> processWebhookMessage(json);
      default -> logger.warn("Unsupported message type: {}", messageType);
    }
  }

  private void processServerStatusMessage(JsonNode json) {
    String serverName = json.path("serverName").asText();
    String status = json.path("status").asText(); // online, offline, starting

    TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
    if (channel != null) {
      String emoji = switch (status) {
        case "online" -> "🟢";
        case "offline" -> "🔴";
        case "starting" -> "🟡";
        default -> "⚪";
      };

      channel.sendMessage(emoji + " **" + serverName + "** The server is " +
          switch (status) {
            case "online" -> "Now online!";
            case "offline" -> "Now offline!";
            case "starting" -> "Starting up...";
            default -> "Status is changed: " + status;
          }).queue();
    }

    logger.info("Updated server status: {} = {}", serverName, status);
  }

  private void processPlayerRequestMessage(JsonNode json) {
    String playerName = json.path("playerName").asText();
    String serverName = json.path("serverName").asText();
    String requestId = json.path("requestId").asText();

    TextChannel adminChannel = jda.getTextChannelById(config.getDiscordAdminChannelId());
    if (adminChannel != null) {
      String message = "**Request Server Starting**\\n" +
          "Player: " + playerName + "\\n" +
          "Server: " + serverName + "\\n" +
          "Request ID: " + requestId;

      adminChannel.sendMessage(message)
          .addActionRow(
              net.dv8tion.jda.api.interactions.components.buttons.Button.success("reqOK", "Approve"),
              net.dv8tion.jda.api.interactions.components.buttons.Button.danger("reqCancel", "Reject"))
          .queue();
    }

    logger.info("Received player request: {} is requested for {} server", playerName, serverName);
  }

  private void processBroadcastMessage(JsonNode json) {
    String content = json.path("content").asText();
    boolean isChat = json.path("isChat").asBoolean(false);

    String channelId = isChat ? config.getDiscordChatChannelId() : config.getDiscordChannelId();
    TextChannel channel = jda.getTextChannelById(channelId);

    if (channel != null) {
      channel.sendMessage(content).queue();
    }

    logger.info("Sent broadcast message: {} (chat={})", content, isChat);
  }

  private void processEmbedMessage(JsonNode json) {
    String content = json.path("content").asText();
    int color = json.path("color").asInt(ColorUtil.GREEN.getRGB());
    String channelId = json.path("channelId").asText(config.getDiscordChannelId());
    String messageId = json.path("messageId").asText("");
    boolean shouldEdit = json.path("edit").asBoolean(false);

    TextChannel channel = jda.getTextChannelById(channelId);
    if (channel == null) {
      logger.warn("Couldn't find the channel: {}", channelId);
      return;
    }

    EmbedBuilder embed = new EmbedBuilder()
        .setDescription(content)
        .setColor(color);

    if (shouldEdit && !messageId.isEmpty()) {
      // メッセージ編集
      channel.editMessageEmbedsById(messageId, embed.build()).queue(
          success -> logger.debug("Edit embed message: {}", messageId),
          error -> logger.error("Failed to edit embed message: {}", messageId, error));
    } else {
      // 新規送信
      channel.sendMessageEmbeds(embed.build()).queue(
          message -> {
            logger.debug("Sent the embed message: {}", message.getId());
            // メッセージIDを保存（必要に応じて）
          },
          error -> logger.error("Failed to send an embed message", error));
    }
  }

  private void processWebhookMessage(JsonNode json) {
    String userName = json.path("userName").asText();
    // String avatarUrl = json.path("avatarUrl").asText();
    String content = json.path("content").asText();

    // Webhookの実装はDiscordEventListenerで処理されるため、ここでは基本的な送信のみ
    TextChannel chatChannel = jda.getTextChannelById(config.getDiscordChatChannelId());
    if (chatChannel != null) {
      chatChannel.sendMessage(content).queue();
      logger.info("Sent webhook message: {}", userName);
    }
  }

  private void processPlayerEventMessage(JsonNode json) {
    String eventType = json.path("eventType").asText();
    String playerName = json.path("playerName").asText();
    String playerUuid = json.path("playerUuid").asText();
    String serverName = json.path("serverName").asText("");

    // DEBUG: 受信したイベントタイプを全て記録
    logger.info("DEBUG processPlayerEventMessage: eventType={}, player={}, server={}",
                eventType, playerName, serverName);

    switch (eventType) {
      case "join", "test_join" -> processPlayerJoin(playerName, playerUuid, serverName);
      case "leave" -> processPlayerLeave(playerName, playerUuid, serverName);
      case "move" -> processPlayerMove(playerName, playerUuid, serverName);
      case "chat" -> processPlayerChat(json);
      default -> logger.warn("Unsupported player event type: {}", eventType);
    }
  }

  private void processPlayerJoin(String playerName, String playerUuid, String serverName) {
    long joinTimestamp = System.currentTimeMillis();
    String joinTime = formatJapanTime(joinTimestamp);
    String joinEmoji = getCustomEmoji("join");

    // 既存のメッセージIDとメッセージ内容を取得
    String messageId = messageIdManager.getPlayerMessageId(playerUuid);
    String existingContent = messageIdManager.getPlayerMessageContent(playerUuid);

    // 既存メッセージに"Exit"が含まれている場合 → 新規Join扱い
    if (messageId != null && existingContent != null && existingContent.contains("Exit")) {
      logger.info("Player {} has Exit in existing message, creating new Join message", playerName);
      messageIdManager.removePlayerMessageId(playerUuid); // 古いメッセージIDを削除
      // ここでmessageIdをnullにせず、そのまま新規Join処理を続行
      // processPlayerMoveは呼ばない
    } else if (messageId != null) {
      // Exitが含まれていない既存メッセージIDがある場合のみ → Move処理
      logger.info("Player {} re-joined, treating as move to {}", playerName, serverName);
      processPlayerMove(playerName, playerUuid, serverName);
      return;
    }

    logger.info("Processing as new Join for player {}", playerName);

    // test-uuidなど無効なUUIDの場合はデフォルト絵文字を使用
    if (isInvalidUuid(playerUuid)) {
      emojiManager.createOrGetEmojiId(config.getBEDefaultEmojiName())
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(config.getBEDefaultEmojiName(), emojiId);
            String content = (emojiString != null ? emojiString + " " : "") + playerName + " is joined at " + serverName
                + " server\n" + joinEmoji + " " + joinTime;

            EmbedBuilder embed = new EmbedBuilder()
                .setDescription(content)
                .setColor(ColorUtil.GREEN.getRGB());

            TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
            if (channel != null) {
              channel.sendMessageEmbeds(embed.build()).queue(
                  message -> messageIdManager.putPlayerMessage(playerUuid, message.getId(), content));
            }
          });
    } else {
      emojiManager.createOrGetEmojiId(playerName, "https://minotar.net/avatar/" + playerUuid)
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(playerName, emojiId);
            String content = (emojiString != null ? emojiString + " " : "") + playerName + " is joined at " + serverName
                + " server\n" + joinEmoji + " " + joinTime;

            EmbedBuilder embed = new EmbedBuilder()
                .setDescription(content)
                .setColor(ColorUtil.GREEN.getRGB());

            TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
            if (channel != null) {
              channel.sendMessageEmbeds(embed.build()).queue(
                  message -> messageIdManager.putPlayerMessage(playerUuid, message.getId(), content));
            }
          });
    }
  }

  private void processPlayerLeave(String playerName, String playerUuid, String serverName) {
    String messageId = messageIdManager.getPlayerMessageId(playerUuid);
    String existingContent = messageIdManager.getPlayerMessageContent(playerUuid);
    Long joinTimestamp = messageIdManager.getPlayerJoinTimestamp(playerUuid);
    String exitEmoji = getCustomEmoji("exit");
    String alarmClockEmoji = getCustomEmoji("alarm_clock");

    if (isInvalidUuid(playerUuid)) {
      emojiManager.createOrGetEmojiId(config.getBEDefaultEmojiName())
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(config.getBEDefaultEmojiName(), emojiId);

            String content;
            String finalMessageId = messageId;
            if (messageId != null && existingContent != null && !existingContent.isEmpty()) {
              // 既存内容に退出情報を追記
              String newContent = existingContent + "\n\n" + exitEmoji + " Exited from " + serverName + " server";

              // プレイ時間を追加
              if (joinTimestamp != null) {
                long playtimeMillis = System.currentTimeMillis() - joinTimestamp;
                String playtime = formatPlaytime(playtimeMillis);
                newContent += "\n" + alarmClockEmoji + " プレイ時間: " + playtime;
              }

              // 文字数チェック：4096文字超過の場合は新規メッセージとして送信
              if (isContentTooLong(newContent)) {
                content = exitEmoji + " " + playerName + " exited from " + serverName + " server";
                if (joinTimestamp != null) {
                  long playtimeMillis = System.currentTimeMillis() - joinTimestamp;
                  String playtime = formatPlaytime(playtimeMillis);
                  content += "\n" + alarmClockEmoji + " プレイ時間: " + playtime;
                }
                finalMessageId = null; // 強制的に新規メッセージ
                logger.info("Content too long for player {}, creating new message", playerName);
              } else {
                content = newContent;
              }
            } else {
              // 新規メッセージ（Join情報がない場合）
              content = (emojiString != null ? emojiString + " " : "") + playerName + " is exited from " + serverName
                  + " server";
            }

            if (finalMessageId != null) {
              // 既存メッセージを編集
              TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
              if (channel != null) {
                EmbedBuilder embed = new EmbedBuilder()
                    .setDescription(content)
                    .setColor(ColorUtil.RED.getRGB());

                // Exit時刻を記録（再Join時のMove処理判定用）
                long exitTs = System.currentTimeMillis();
                messageIdManager.setPlayerExitTimestamp(playerUuid, exitTs);
                logger.info("DEBUG processPlayerLeave (BE): player={}, messageId={}, setExitTimestamp={}, getExitTimestamp={}",
                            playerName, finalMessageId, exitTs, messageIdManager.getPlayerExitTimestamp(playerUuid));

                channel.editMessageEmbedsById(finalMessageId, embed.build()).queue();
                messageIdManager.updatePlayerMessageContent(playerUuid, content);
                // メッセージIDは削除せず保持（再Join時にMove処理するため）
              }
            } else {
              // 新規メッセージ
              TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
              if (channel != null) {
                EmbedBuilder embed = new EmbedBuilder()
                    .setDescription(content)
                    .setColor(ColorUtil.RED.getRGB());

                channel.sendMessageEmbeds(embed.build()).queue(
                    message -> {
                      // 新規メッセージを送信後、すぐに削除（Leaveなので）
                      messageIdManager.removePlayerMessageId(playerUuid);
                    });
              }
            }
          });
    } else {
      emojiManager.createOrGetEmojiId(playerName, "https://minotar.net/avatar/" + playerUuid)
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(playerName, emojiId);

            String content;
            String finalMessageId = messageId;
            if (messageId != null && existingContent != null && !existingContent.isEmpty()) {
              // 既存内容に退出情報を追記
              String newContent = existingContent + "\n\n" + exitEmoji + " Exited from " + serverName + " server";

              // プレイ時間を追加
              if (joinTimestamp != null) {
                long playtimeMillis = System.currentTimeMillis() - joinTimestamp;
                String playtime = formatPlaytime(playtimeMillis);
                newContent += "\n" + alarmClockEmoji + " プレイ時間: " + playtime;
              }

              // 文字数チェック：4096文字超過の場合は新規メッセージとして送信
              if (isContentTooLong(newContent)) {
                content = exitEmoji + " " + playerName + " exited from " + serverName + " server";
                if (joinTimestamp != null) {
                  long playtimeMillis = System.currentTimeMillis() - joinTimestamp;
                  String playtime = formatPlaytime(playtimeMillis);
                  content += "\n" + alarmClockEmoji + " プレイ時間: " + playtime;
                }
                finalMessageId = null; // 強制的に新規メッセージ
                logger.info("Content too long for player {}, creating new message", playerName);
              } else {
                content = newContent;
              }
            } else {
              // 新規メッセージ（Join情報がない場合）
              content = (emojiString != null ? emojiString + " " : "") + playerName + " is exited from " + serverName
                  + " server";
            }

            if (finalMessageId != null) {
              // 既存メッセージを編集
              TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
              if (channel != null) {
                EmbedBuilder embed = new EmbedBuilder()
                    .setDescription(content)
                    .setColor(ColorUtil.RED.getRGB());

                // Exit時刻を記録（再Join時のMove処理判定用）
                long exitTs = System.currentTimeMillis();
                messageIdManager.setPlayerExitTimestamp(playerUuid, exitTs);
                logger.info("DEBUG processPlayerLeave (Java): player={}, messageId={}, setExitTimestamp={}, getExitTimestamp={}",
                            playerName, finalMessageId, exitTs, messageIdManager.getPlayerExitTimestamp(playerUuid));

                channel.editMessageEmbedsById(finalMessageId, embed.build()).queue();
                messageIdManager.updatePlayerMessageContent(playerUuid, content);
                // メッセージIDは削除せず保持（再Join時にMove処理するため）
              }
            } else {
              // 新規メッセージ
              TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
              if (channel != null) {
                EmbedBuilder embed = new EmbedBuilder()
                    .setDescription(content)
                    .setColor(ColorUtil.RED.getRGB());

                channel.sendMessageEmbeds(embed.build()).queue(
                    message -> {
                      // 新規メッセージを送信後、すぐに削除（Leaveなので）
                      messageIdManager.removePlayerMessageId(playerUuid);
                    });
              }
            }
          });
    }
  }

  private void processPlayerMove(String playerName, String playerUuid, String serverName) {
    String messageId = messageIdManager.getPlayerMessageId(playerUuid);
    String existingContent = messageIdManager.getPlayerMessageContent(playerUuid);
    String moveEmoji = getCustomEmoji("move");

    // 既存メッセージに"Exit"が含まれている場合 → 新規Join扱いにする
    if (messageId != null && existingContent != null && existingContent.contains("Exit")) {
      logger.info("Player {} has Exit in existing message, treating Move as new Join", playerName);
      processPlayerJoin(playerName, playerUuid, serverName);
      return;
    }

    if (isInvalidUuid(playerUuid)) {
      emojiManager.createOrGetEmojiId(config.getBEDefaultEmojiName())
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(config.getBEDefaultEmojiName(), emojiId);

            String content;
            String finalMessageId = messageId;
            if (messageId != null && existingContent != null && !existingContent.isEmpty()) {
              // 既存内容に移動情報を追記
              String newContent = existingContent + "\n\n" + moveEmoji + " Moved to " + serverName + " server";

              // 文字数チェック：4096文字超過の場合は新規メッセージとして送信
              if (isContentTooLong(newContent)) {
                content = moveEmoji + " " + playerName + " moved to " + serverName + " server";
                finalMessageId = null; // 強制的に新規メッセージ
                logger.info("Content too long for player {}, creating new message", playerName);
              } else {
                content = newContent;
              }
            } else {
              // 新規メッセージ（Join情報がない場合）
              content = (emojiString != null ? emojiString + " " : "") + playerName + " is moved into " + serverName
                  + " server";
            }

            EmbedBuilder embed = new EmbedBuilder()
                .setDescription(content)
                .setColor(ColorUtil.BLUE.getRGB());

            if (finalMessageId != null) {
              // 既存メッセージを編集
              TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
              if (channel != null) {
                channel.editMessageEmbedsById(finalMessageId, embed.build()).queue(
                    success -> messageIdManager.updatePlayerMessageContent(playerUuid, content));
              }
            } else {
              // 新規メッセージ
              TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
              if (channel != null) {
                channel.sendMessageEmbeds(embed.build()).queue(
                    message -> messageIdManager.putPlayerMessage(playerUuid, message.getId(), content));
              }
            }
          });
    } else {
      emojiManager.createOrGetEmojiId(playerName, "https://minotar.net/avatar/" + playerUuid)
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(playerName, emojiId);

            String content;
            String finalMessageId = messageId;
            if (messageId != null && existingContent != null && !existingContent.isEmpty()) {
              // 既存内容に移動情報を追記
              String newContent = existingContent + "\n\n" + moveEmoji + " Moved to " + serverName + " server";

              // 文字数チェック：4096文字超過の場合は新規メッセージとして送信
              if (isContentTooLong(newContent)) {
                content = moveEmoji + " " + playerName + " moved to " + serverName + " server";
                finalMessageId = null; // 強制的に新規メッセージ
                logger.info("Content too long for player {}, creating new message", playerName);
              } else {
                content = newContent;
              }
            } else {
              // 新規メッセージ（Join情報がない場合）
              content = (emojiString != null ? emojiString + " " : "") + playerName + " is moved into " + serverName
                  + " server";
            }

            EmbedBuilder embed = new EmbedBuilder()
                .setDescription(content)
                .setColor(ColorUtil.BLUE.getRGB());

            if (finalMessageId != null) {
              // 既存メッセージを編集
              TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
              if (channel != null) {
                channel.editMessageEmbedsById(finalMessageId, embed.build()).queue(
                    success -> messageIdManager.updatePlayerMessageContent(playerUuid, content));
              }
            } else {
              // 新規メッセージ
              TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
              if (channel != null) {
                channel.sendMessageEmbeds(embed.build()).queue(
                    message -> messageIdManager.putPlayerMessage(playerUuid, message.getId(), content));
              }
            }
          });
    }
  }

  private void processPlayerChat(JsonNode json) {
    String playerName = json.path("playerName").asText();
    String playerUuid = json.path("playerUuid").asText();
    String chatMessage = json.path("message").asText();

    String chatMessageId = messageIdManager.getChatMessageId();
    String existingChatContent = messageIdManager.getChatMessageContent();

    if (isInvalidUuid(playerUuid)) {
      emojiManager.createOrGetEmojiId(config.getBEDefaultEmojiName())
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(config.getBEDefaultEmojiName(), emojiId);
            String newLine = "<" + (emojiString != null ? emojiString + " " : "") + playerName + "> " + chatMessage;

            String content;
            String finalChatMessageId = chatMessageId;
            TextChannel chatChannel = jda.getTextChannelById(config.getDiscordChatChannelId());

            if (chatMessageId != null && existingChatContent != null && !existingChatContent.isEmpty()) {
              // 既存チャット内容に追記
              String newContent = existingChatContent + "\n" + newLine;

              // 文字数チェック：4096文字超過の場合は新規メッセージとして送信
              if (isContentTooLong(newContent)) {
                content = newLine;
                finalChatMessageId = null; // 強制的に新規メッセージ
                logger.info("Chat content too long, creating new message");
              } else {
                // Discord新規メッセージチェック：グローバルメッセージID以降にDiscordメッセージがあれば新規送信
                if (chatChannel != null) {
                  long globalTimestamp = messageIdManager.getChatMessageTimestamp();
                  if (hasNewDiscordMessages(chatChannel, chatMessageId, globalTimestamp)) {
                    finalChatMessageId = null; // 新規メッセージとして送信
                    content = newLine; // 新規メッセージなので追記なし
                    logger.info("New Discord messages detected after global message, creating new message instead of editing");
                  } else {
                    content = newContent;
                  }
                } else {
                  content = newContent;
                }
              }
            } else {
              // 新規メッセージ
              content = newLine;
            }

            String finalContent = content;
            EmbedBuilder embed = new EmbedBuilder()
                .setDescription(finalContent)
                .setColor(ColorUtil.GREEN.getRGB());

            if (chatChannel != null) {
              if (finalChatMessageId != null) {
                // 既存チャットメッセージを編集
                chatChannel.editMessageEmbedsById(finalChatMessageId, embed.build()).queue(
                    success -> messageIdManager.setChatMessageContent(finalContent));
              } else {
                // 新規チャットメッセージ
                chatChannel.sendMessageEmbeds(embed.build()).queue(
                    message -> {
                      messageIdManager.setChatMessageId(message.getId());
                      messageIdManager.setChatMessageContent(finalContent);
                    });
              }
            }
          });
    } else {
      emojiManager.createOrGetEmojiId(playerName, "https://minotar.net/avatar/" + playerUuid)
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(playerName, emojiId);
            String newLine = "<" + (emojiString != null ? emojiString + " " : "") + playerName + "> " + chatMessage;

            String content;
            String finalChatMessageId = chatMessageId;
            TextChannel chatChannel = jda.getTextChannelById(config.getDiscordChatChannelId());

            if (chatMessageId != null && existingChatContent != null && !existingChatContent.isEmpty()) {
              // 既存チャット内容に追記
              String newContent = existingChatContent + "\n" + newLine;

              // 文字数チェック：4096文字超過の場合は新規メッセージとして送信
              if (isContentTooLong(newContent)) {
                content = newLine;
                finalChatMessageId = null; // 強制的に新規メッセージ
                logger.info("Chat content too long, creating new message");
              } else {
                // Discord新規メッセージチェック：グローバルメッセージID以降にDiscordメッセージがあれば新規送信
                if (chatChannel != null) {
                  long globalTimestamp = messageIdManager.getChatMessageTimestamp();
                  if (hasNewDiscordMessages(chatChannel, chatMessageId, globalTimestamp)) {
                    finalChatMessageId = null; // 新規メッセージとして送信
                    content = newLine; // 新規メッセージなので追記なし
                    logger.info("New Discord messages detected after global message, creating new message instead of editing");
                  } else {
                    content = newContent;
                  }
                } else {
                  content = newContent;
                }
              }
            } else {
              // 新規メッセージ
              content = newLine;
            }

            String finalContent = content;
            EmbedBuilder embed = new EmbedBuilder()
                .setDescription(finalContent)
                .setColor(ColorUtil.GREEN.getRGB());

            if (chatChannel != null) {
              if (finalChatMessageId != null) {
                // 既存チャットメッセージを編集
                chatChannel.editMessageEmbedsById(finalChatMessageId, embed.build()).queue(
                    success -> messageIdManager.setChatMessageContent(finalContent));
              } else {
                // 新規チャットメッセージ
                chatChannel.sendMessageEmbeds(embed.build()).queue(
                    message -> {
                      messageIdManager.setChatMessageId(message.getId());
                      messageIdManager.setChatMessageContent(finalContent);
                    });
              }
            }
          });
    }
  }

  /**
   * 無効なUUIDかどうかを判定
   */
  private boolean isInvalidUuid(String uuid) {
    if (uuid == null || uuid.isEmpty()) {
      return true;
    }

    // test-uuidや明らかに無効なパターンをチェック
    if (uuid.startsWith("test-") || uuid.equals("test-uuid-12345")) {
      return true;
    }

    // 正規のUUID形式チェック（8-4-4-4-12文字）
    return !uuid.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  }

  /**
   * タイムスタンプを日本時間でフォーマット
   * フォーマット: "MMM d, HH:mm:ss" (例: "Dec 31, 14:30:45")
   */
  private String formatJapanTime(long timestampMillis) {
    ZonedDateTime jst = Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.of("Asia/Tokyo"));
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss", Locale.ENGLISH);
    return jst.format(formatter);
  }

  /**
   * プレイ時間をフォーマット
   * フォーマット: "HH:mm:ss" (例: "01:08:21")
   */
  private String formatPlaytime(long playtimeMillis) {
    long seconds = playtimeMillis / 1000;
    long hours = seconds / 3600;
    long minutes = (seconds % 3600) / 60;
    long secs = seconds % 60;
    return String.format("%02d:%02d:%02d", hours, minutes, secs);
  }

  /**
   * カスタム絵文字を取得（名前から）
   * フォーマット: <:emoji_name:emoji_id>
   */
  private String getCustomEmoji(String emojiName) {
    try {
      var emojis = jda.getEmojisByName(emojiName, true);
      if (!emojis.isEmpty()) {
        var emoji = emojis.get(0);
        return emoji.getFormatted();
      }
    } catch (Exception e) {
      logger.warn("Failed to get custom emoji: {}", emojiName, e);
    }
    return "";
  }

  /**
   * コンテンツの文字数がDiscordの制限を超えているかチェック
   */
  private boolean isContentTooLong(String content) {
    return content != null && content.length() > DISCORD_EMBED_MAX_LENGTH;
  }

  /**
   * グローバルメッセージID以降にDiscordメッセージが投稿されているかチェック
   *
   * @param chatChannel チャットチャンネル
   * @param globalMessageId グローバルメッセージID
   * @param globalMessageTimestamp グローバルメッセージのタイムスタンプ（ミリ秒）
   * @return Discord新規メッセージが存在する場合true
   */
  private boolean hasNewDiscordMessages(TextChannel chatChannel, String globalMessageId, long globalMessageTimestamp) {
    try {
      // メッセージ履歴を取得（最新50件）
      var history = chatChannel.getHistory().retrievePast(50).complete();

      // グローバルメッセージID以降のBot以外のメッセージをカウント
      long newMessageCount = history.stream()
          .filter(msg -> {
            // Bot自身のメッセージは除外
            if (msg.getAuthor().isBot()) {
              return false;
            }

            // グローバルメッセージIDと同じまたはそれより古いメッセージは除外
            long messageTimestamp = msg.getTimeCreated().toInstant().toEpochMilli();
            return messageTimestamp > globalMessageTimestamp;
          })
          .count();

      boolean hasNewMessages = newMessageCount > 0;
      logger.debug("Checked for new Discord messages after global message (id={}, timestamp={}): found {} messages",
                   globalMessageId, globalMessageTimestamp, newMessageCount);

      return hasNewMessages;
    } catch (Exception e) {
      logger.error("Failed to check for new Discord messages", e);
      // エラーの場合は安全側に倒して新規メッセージとして扱う
      return true;
    }
  }

  /**
   * 手動でのDiscord応答送信（必要な場合）
   */
  public void sendResponse(String action, String result, Map<String, Object> data, String source) {
    try {
      Map<String, Object> response = new HashMap<>();
      response.put("type", "discord_response");
      response.put("result", result);
      response.put("action", action);
      response.put("timestamp", System.currentTimeMillis());

      Map<String, Object> responseData = new HashMap<>(data);
      responseData.put("source", source);
      response.put("data", responseData);

      String responseJson = objectMapper.writeValueAsString(response);

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.publish(DISCORD_RESPONSES_CHANNEL, responseJson);
        logger.info("📤 Sent discord reponse: {} - {}", action, result);
      }
    } catch (Exception e) {
      logger.error("An error occurred while sending discord response", e);
    }
  }
}
