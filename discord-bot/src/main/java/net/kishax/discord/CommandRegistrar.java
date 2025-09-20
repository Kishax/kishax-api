package net.kishax.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.kishax.api.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * スラッシュコマンド登録クラス
 */
public class CommandRegistrar {
  private static final Logger logger = LoggerFactory.getLogger(CommandRegistrar.class);

  public static void registerCommands(JDA jda, Configuration config) {
    try {
      // kishaxコマンドの登録
      jda.upsertCommand(
          Commands.slash("kishax", "Kishax commands")
              .addSubcommands(
                  new SubcommandData("image_add_q",
                      "Add an image map at queue. Please specific url as text or file attached)")
                      .addOption(OptionType.STRING, "url", "url for image", false)
                      .addOption(OptionType.ATTACHMENT, "image", "attachment for image", false)
                      .addOption(OptionType.STRING, "title", "title for image map", false)
                      .addOption(OptionType.STRING, "comment", "comment for image map", false)))
          .queue(
              success -> logger.info("Registered slash commands successfully"),
              error -> logger.error("Failed to register slash commands: ", error));

    } catch (Exception e) {
      logger.error("An error occurred while registering discord commands: ", e);
    }
  }
}
