package net.kishax.discord;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.kishax.api.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Discord イベントリスナー
 * リフレクションを使わないシンプルな実装
 */
public class DiscordEventListener extends ListenerAdapter {
  private static final Logger logger = LoggerFactory.getLogger(DiscordEventListener.class);
  private static final String MC_BROADCAST_CHANNEL = "mc_broadcast";

  private final Configuration config;
  private final JedisPool jedisPool;
  private final ObjectMapper objectMapper;

  public DiscordEventListener(Configuration config, JedisPool jedisPool) {
    this.config = config;
    this.jedisPool = jedisPool;
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public void onReady(ReadyEvent event) {
    logger.info("Ready for discord bot: {}", event.getJDA().getSelfUser().getName());

    // プレゼンス設定
    event.getJDA().getPresence().setActivity(
        Activity.playing(config.getDiscordPresenceActivity()));
  }

  @Override
  public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    String commandName = event.getName();
    String subcommandName = event.getSubcommandName();

    logger.debug("The slash command is executed: {} - {}", commandName, subcommandName);

    if ("kishax".equals(commandName)) {
      handleKishaxCommand(event, subcommandName);
    }
  }

  @Override
  public void onButtonInteraction(ButtonInteractionEvent event) {
    String buttonId = event.getComponentId();

    logger.debug("The button is pushed: {}", buttonId);

    switch (buttonId) {
      case "reqOK" -> handleRequestApproval(event);
      case "reqCancel" -> handleRequestReject(event);
      default -> event.reply("Unsupported button detected").setEphemeral(true).queue();
    }
  }

  private void handleKishaxCommand(SlashCommandInteractionEvent event, String subcommand) {
    switch (subcommand) {
      case "image_add_q" -> handleImageAddQueue(event);
      default -> event.reply("Unsupported subcommand detected").setEphemeral(true).queue();
    }
  }

  private void handleImageAddQueue(SlashCommandInteractionEvent event) {
    // 画像マップキューに追加の処理
    String url = event.getOption("url") != null ? event.getOption("url").getAsString() : null;
    String title = event.getOption("title") != null ? event.getOption("title").getAsString() : "No Title";
    String comment = event.getOption("comment") != null ? event.getOption("comment").getAsString() : "";

    // 添付ファイルの処理
    if (event.getOption("image") != null) {
      var attachment = event.getOption("image").getAsAttachment();
      url = attachment.getUrl();
    }

    if (url == null) {
      event.reply("Specific url for image or file attached").setEphemeral(true).queue();
      return;
    }

    // TODO: 実際の画像マップキューに追加する処理を実装
    event.reply("Added that image map at queue\\nurl: " + url + "\\ntitle: " + title)
        .setEphemeral(true)
        .queue();

    logger.debug("Added an image map at queue: URL={}, Title={}, Comment={}", url, title, comment);
  }

  private void handleRequestApproval(ButtonInteractionEvent event) {
    // リクエスト承認の処理
    event.reply("The request is approved").setEphemeral(true).queue();
    logger.info("Request approver: user={}", event.getUser().getName());

    // TODO: 実際の承認処理を実装
  }

  private void handleRequestReject(ButtonInteractionEvent event) {
    // リクエスト拒否の処理
    event.reply("The request is rejected").setEphemeral(true).queue();
    logger.info("Request rejected person: user={}", event.getUser().getName());

    // TODO: 実際の拒否処理を実装
  }

  @Override
  public void onMessageReceived(MessageReceivedEvent event) {
    // Botメッセージは無視（無限ループ防止）
    if (event.getAuthor().isBot()) {
      return;
    }

    // DISCORD_CHAT_CHANNEL_IDのメッセージのみ処理
    String chatChannelId = config.getDiscordChatChannelId();
    if (chatChannelId == null || !event.getChannel().getId().equals(chatChannelId)) {
      return;
    }

    // Discord→MC ブロードキャストメッセージを作成
    String author = event.getAuthor().getName();
    String authorId = event.getAuthor().getId();
    String content = event.getMessage().getContentDisplay();

    // 画像添付のチェック
    List<Message.Attachment> attachments = event.getMessage().getAttachments();
    List<String> imageUrls = new ArrayList<>();

    for (Message.Attachment attachment : attachments) {
      if (attachment.isImage()) {
        imageUrls.add(attachment.getUrl());
      }
    }

    logger.info("Discord message received for MC broadcast: author={}, content={}, images={}",
                author, content, imageUrls.size());

    try {
      // JSONメッセージ作成
      ObjectNode messageJson = objectMapper.createObjectNode();
      messageJson.put("type", "discord_broadcast");
      messageJson.put("author", author);
      messageJson.put("authorId", authorId);
      messageJson.put("content", content);
      messageJson.put("timestamp", Instant.now().toString());
      messageJson.put("hasImages", !imageUrls.isEmpty());

      // 画像URLを配列として追加
      if (!imageUrls.isEmpty()) {
        ArrayNode urlsArray = objectMapper.createArrayNode();
        for (String url : imageUrls) {
          urlsArray.add(url);
        }
        messageJson.set("imageUrls", urlsArray);
      }

      // Redisにパブリッシュ
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.publish(MC_BROADCAST_CHANNEL, messageJson.toString());
        logger.info("Published Discord message to Redis channel: {}", MC_BROADCAST_CHANNEL);
      }
    } catch (Exception e) {
      logger.error("Failed to publish Discord message to Redis", e);
    }
  }
}
