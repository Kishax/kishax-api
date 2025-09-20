package net.kishax.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.kishax.api.common.Configuration;
import net.kishax.discord.ColorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.HashMap;
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

    switch (eventType) {
      case "join", "test_join" -> processPlayerJoin(playerName, playerUuid, serverName);
      case "leave" -> processPlayerLeave(playerName, playerUuid, serverName);
      case "move" -> processPlayerMove(playerName, playerUuid, serverName);
      case "chat" -> processPlayerChat(json);
      default -> logger.warn("Unsupported player event type: {}", eventType);
    }
  }

  private void processPlayerJoin(String playerName, String playerUuid, String serverName) {
    // test-uuidなど無効なUUIDの場合はデフォルト絵文字を使用
    if (isInvalidUuid(playerUuid)) {
      emojiManager.createOrGetEmojiId(config.getBEDefaultEmojiName())
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(config.getBEDefaultEmojiName(), emojiId);
            String content = (emojiString != null ? emojiString : "") + playerName + " is joined at " + serverName
                + " server";

            EmbedBuilder embed = new EmbedBuilder()
                .setDescription(content)
                .setColor(ColorUtil.GREEN.getRGB());

            TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
            if (channel != null) {
              channel.sendMessageEmbeds(embed.build()).queue(
                  message -> messageIdManager.putPlayerMessageId(playerUuid, message.getId()));
            }
          });
    } else {
      emojiManager.createOrGetEmojiId(playerName, "https://minotar.net/avatar/" + playerUuid)
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(playerName, emojiId);
            String content = (emojiString != null ? emojiString : "") + playerName + " is joined at " + serverName
                + " server";

            EmbedBuilder embed = new EmbedBuilder()
                .setDescription(content)
                .setColor(ColorUtil.GREEN.getRGB());

            TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
            if (channel != null) {
              channel.sendMessageEmbeds(embed.build()).queue(
                  message -> messageIdManager.putPlayerMessageId(playerUuid, message.getId()));
            }
          });
    }
  }

  private void processPlayerLeave(String playerName, String playerUuid, String serverName) {
    String messageId = messageIdManager.getPlayerMessageId(playerUuid);

    if (isInvalidUuid(playerUuid)) {
      emojiManager.createOrGetEmojiId(config.getBEDefaultEmojiName())
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(config.getBEDefaultEmojiName(), emojiId);
            String content = (emojiString != null ? emojiString : "") + playerName + " is exited from " + serverName
                + " server";

            if (messageId != null) {
              // 既存メッセージを編集
              TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
              if (channel != null) {
                EmbedBuilder embed = new EmbedBuilder()
                    .setDescription(content)
                    .setColor(ColorUtil.RED.getRGB());

                channel.editMessageEmbedsById(messageId, embed.build()).queue();
                messageIdManager.removePlayerMessageId(playerUuid);
              }
            }
          });
    } else {
      emojiManager.createOrGetEmojiId(playerName, "https://minotar.net/avatar/" + playerUuid)
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(playerName, emojiId);
            String content = (emojiString != null ? emojiString : "") + playerName + " is exited from " + serverName
                + " server";

            if (messageId != null) {
              // 既存メッセージを編集
              TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
              if (channel != null) {
                EmbedBuilder embed = new EmbedBuilder()
                    .setDescription(content)
                    .setColor(ColorUtil.RED.getRGB());

                channel.editMessageEmbedsById(messageId, embed.build()).queue();
                messageIdManager.removePlayerMessageId(playerUuid);
              }
            }
          });
    }
  }

  private void processPlayerMove(String playerName, String playerUuid, String serverName) {
    String messageId = messageIdManager.getPlayerMessageId(playerUuid);

    if (isInvalidUuid(playerUuid)) {
      emojiManager.createOrGetEmojiId(config.getBEDefaultEmojiName())
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(config.getBEDefaultEmojiName(), emojiId);
            String content = (emojiString != null ? emojiString : "") + playerName + " is moved into " + serverName
                + " server";

            EmbedBuilder embed = new EmbedBuilder()
                .setDescription(content)
                .setColor(ColorUtil.BLUE.getRGB());

            if (messageId != null) {
              // 既存メッセージを編集
              TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
              if (channel != null) {
                channel.editMessageEmbedsById(messageId, embed.build()).queue();
              }
            } else {
              // 新規メッセージ
              TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
              if (channel != null) {
                channel.sendMessageEmbeds(embed.build()).queue(
                    message -> messageIdManager.putPlayerMessageId(playerUuid, message.getId()));
              }
            }
          });
    } else {
      emojiManager.createOrGetEmojiId(playerName, "https://minotar.net/avatar/" + playerUuid)
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(playerName, emojiId);
            String content = (emojiString != null ? emojiString : "") + playerName + " is moved into " + serverName
                + " server";

            EmbedBuilder embed = new EmbedBuilder()
                .setDescription(content)
                .setColor(ColorUtil.BLUE.getRGB());

            if (messageId != null) {
              // 既存メッセージを編集
              TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
              if (channel != null) {
                channel.editMessageEmbedsById(messageId, embed.build()).queue();
              }
            } else {
              // 新規メッセージ
              TextChannel channel = jda.getTextChannelById(config.getDiscordChannelId());
              if (channel != null) {
                channel.sendMessageEmbeds(embed.build()).queue(
                    message -> messageIdManager.putPlayerMessageId(playerUuid, message.getId()));
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

    if (isInvalidUuid(playerUuid)) {
      emojiManager.createOrGetEmojiId(config.getBEDefaultEmojiName())
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(config.getBEDefaultEmojiName(), emojiId);
            String content = "<" + (emojiString != null ? emojiString : "") + playerName + "> " + chatMessage;

            EmbedBuilder embed = new EmbedBuilder()
                .setDescription(content)
                .setColor(ColorUtil.GREEN.getRGB());

            TextChannel chatChannel = jda.getTextChannelById(config.getDiscordChatChannelId());
            if (chatChannel != null) {
              if (chatMessageId != null) {
                // 既存チャットメッセージを編集
                chatChannel.editMessageEmbedsById(chatMessageId, embed.build()).queue();
              } else {
                // 新規チャットメッセージ
                chatChannel.sendMessageEmbeds(embed.build()).queue(
                    message -> messageIdManager.setChatMessageId(message.getId()));
              }
            }
          });
    } else {
      emojiManager.createOrGetEmojiId(playerName, "https://minotar.net/avatar/" + playerUuid)
          .thenAccept(emojiId -> {
            String emojiString = emojiManager.getEmojiString(playerName, emojiId);
            String content = "<" + (emojiString != null ? emojiString : "") + playerName + "> " + chatMessage;

            EmbedBuilder embed = new EmbedBuilder()
                .setDescription(content)
                .setColor(ColorUtil.GREEN.getRGB());

            TextChannel chatChannel = jda.getTextChannelById(config.getDiscordChatChannelId());
            if (chatChannel != null) {
              if (chatMessageId != null) {
                // 既存チャットメッセージを編集
                chatChannel.editMessageEmbedsById(chatMessageId, embed.build()).queue();
              } else {
                // 新規チャットメッセージ
                chatChannel.sendMessageEmbeds(embed.build()).queue(
                    message -> messageIdManager.setChatMessageId(message.getId()));
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
