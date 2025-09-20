package net.kishax.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
// SQS Message import - JDA Message conflicts are handled with full class names
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.kishax.api.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SQSメッセージプロセッサー
 * SQSからメッセージを受信してDiscordに送信
 */
public class SqsMessageProcessor {
  private static final Logger logger = LoggerFactory.getLogger(SqsMessageProcessor.class);

  private final SqsClient sqsClient;
  private final JDA jda;
  private final Configuration config;
  private final ObjectMapper objectMapper;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final EmojiManager emojiManager;
  private final MessageIdManager messageIdManager;

  public SqsMessageProcessor(SqsClient sqsClient, JDA jda, Configuration config) {
    this.sqsClient = sqsClient;
    this.jda = jda;
    this.config = config;
    this.objectMapper = new ObjectMapper();
    this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "SQS-Processor");
      t.setDaemon(true);
      return t;
    });
    this.emojiManager = new EmojiManager(jda, config);
    this.messageIdManager = new MessageIdManager();

    // デフォルト絵文字IDを初期化
    emojiManager.updateDefaultEmojiId();
  }

  public void start() {
    if (running.compareAndSet(false, true)) {
      logger.info("Starting the processer of sqs message...");
      executor.scheduleWithFixedDelay(this::pollMessages, 0, 5, TimeUnit.SECONDS);
    }
  }

  public void stop() {
    if (running.compareAndSet(true, false)) {
      logger.info("Stopping the processer of sqs message...");
      executor.shutdown();
      try {
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
          executor.shutdownNow();
        }
      } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  private void pollMessages() {
    if (!running.get()) {
      return;
    }

    try {
      ReceiveMessageRequest request = ReceiveMessageRequest.builder()
          .queueUrl(config.getSqsQueueUrl())
          .maxNumberOfMessages(config.getSqsMaxMessages())
          .waitTimeSeconds(config.getSqsWaitTimeSeconds())
          .build();

      ReceiveMessageResponse response = sqsClient.receiveMessage(request);
      List<software.amazon.awssdk.services.sqs.model.Message> messages = response.messages();

      if (!messages.isEmpty()) {
        logger.debug("Received the sqs message: {} messages", messages.size());
      }

      for (software.amazon.awssdk.services.sqs.model.Message message : messages) {
        try {
          processMessage(message);
          deleteMessage(message);
        } catch (Exception e) {
          logger.error("An error occurred while processing sqs message: {}", message.messageId(), e);
        }
      }

    } catch (Exception e) {
      logger.error("An error occurred while polling sqs message", e);
    }
  }

  private void processMessage(software.amazon.awssdk.services.sqs.model.Message message) throws Exception {
    String body = message.body();
    logger.debug("Starting process of sqs message: {}", message.messageId());
    processMessage(body);
  }

  /**
   * メッセージ本文からJSON処理（Redis経由でも使用）
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

  private void processPlayerEventMessage(JsonNode json) {
    String eventType = json.path("eventType").asText();
    String playerName = json.path("playerName").asText();
    String playerUuid = json.path("playerUuid").asText();
    String serverName = json.path("serverName").asText("");

    switch (eventType) {
      case "join" -> processPlayerJoin(playerName, playerUuid, serverName);
      case "leave" -> processPlayerLeave(playerName, playerUuid, serverName);
      case "move" -> processPlayerMove(playerName, playerUuid, serverName);
      case "chat" -> processPlayerChat(json);
      default -> logger.warn("Unsupported player event type: {}", eventType);
    }
  }

  private void processPlayerJoin(String playerName, String playerUuid, String serverName) {
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

  private void processPlayerLeave(String playerName, String playerUuid, String serverName) {
    String messageId = messageIdManager.getPlayerMessageId(playerUuid);

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

  private void processPlayerMove(String playerName, String playerUuid, String serverName) {
    String messageId = messageIdManager.getPlayerMessageId(playerUuid);

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

  private void processPlayerChat(JsonNode json) {
    String playerName = json.path("playerName").asText();
    String playerUuid = json.path("playerUuid").asText();
    String chatMessage = json.path("message").asText();

    String chatMessageId = messageIdManager.getChatMessageId();

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

  private void deleteMessage(software.amazon.awssdk.services.sqs.model.Message message) {
    try {
      DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
          .queueUrl(config.getSqsQueueUrl())
          .receiptHandle(message.receiptHandle())
          .build();

      sqsClient.deleteMessage(deleteRequest);
      logger.debug("Deleted message: {}", message.messageId());
    } catch (Exception e) {
      logger.error("An error occurred while deleting message: {}", message.messageId(), e);
    }
  }
}
