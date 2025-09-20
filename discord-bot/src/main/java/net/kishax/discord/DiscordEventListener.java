package net.kishax.discord;

import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.kishax.api.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discord イベントリスナー
 * リフレクションを使わないシンプルな実装
 */
public class DiscordEventListener extends ListenerAdapter {
  private static final Logger logger = LoggerFactory.getLogger(DiscordEventListener.class);

  private final Configuration config;

  public DiscordEventListener(Configuration config) {
    this.config = config;
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
}
