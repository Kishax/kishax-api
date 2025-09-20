package net.kishax.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.dv8tion.jda.api.entities.Icon;
import net.kishax.api.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Discord絵文字管理クラス
 * リフレクションを使わずJDAを直接使用
 */
public class EmojiManager {
  private static final Logger logger = LoggerFactory.getLogger(EmojiManager.class);

  private final JDA jda;
  private final Configuration config;
  private String defaultEmojiId = null;

  public EmojiManager(JDA jda, Configuration config) {
    this.jda = jda;
    this.config = config;
  }

  /**
   * デフォルト絵文字IDを更新
   */
  public CompletableFuture<Void> updateDefaultEmojiId() {
    String defaultEmojiName = config.getBEDefaultEmojiName();
    if (defaultEmojiName == null || defaultEmojiName.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }

    return createOrGetEmojiId(defaultEmojiName)
        .thenAccept(id -> this.defaultEmojiId = id)
        .exceptionally(ex -> {
          logger.error("Failed to update default emoji id", ex);
          return null;
        });
  }

  /**
   * 複数の絵文字IDを取得
   */
  public CompletableFuture<Map<String, String>> getEmojiIds(List<String> emojiNames) {
    if (emojiNames == null || emojiNames.isEmpty()) {
      return CompletableFuture.completedFuture(new HashMap<>());
    }

    Guild guild = getGuild();
    if (guild == null) {
      return CompletableFuture.completedFuture(new HashMap<>());
    }

    Map<String, String> emojiMap = new HashMap<>();
    List<RichCustomEmoji> emojis = guild.getEmojis();

    for (String emojiName : emojiNames) {
      Optional<RichCustomEmoji> emoji = emojis.stream()
          .filter(e -> e.getName().equals(emojiName))
          .findFirst();

      emojiMap.put(emojiName, emoji.map(RichCustomEmoji::getId).orElse(null));
    }

    return CompletableFuture.completedFuture(emojiMap);
  }

  /**
   * 絵文字を作成または取得
   */
  public CompletableFuture<String> createOrGetEmojiId(String emojiName, String imageUrl) {
    if (emojiName == null || emojiName.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }

    Guild guild = getGuild();
    if (guild == null) {
      return CompletableFuture.completedFuture(null);
    }

    // 既存の絵文字を確認
    Optional<RichCustomEmoji> existingEmoji = guild.getEmojis().stream()
        .filter(emoji -> emoji.getName().equals(emojiName))
        .findFirst();

    if (existingEmoji.isPresent()) {
      return CompletableFuture.completedFuture(existingEmoji.get().getId());
    }

    // 新規作成
    if (emojiName.startsWith(".")) {
      // デフォルト絵文字を返す
      return createOrGetEmojiId(config.getBEDefaultEmojiName());
    }

    if (imageUrl == null || imageUrl.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }

    return downloadAndCreateEmoji(guild, emojiName, imageUrl);
  }

  /**
   * 絵文字を作成または取得（画像URLなし）
   */
  public CompletableFuture<String> createOrGetEmojiId(String emojiName) {
    return createOrGetEmojiId(emojiName, null);
  }

  /**
   * 絵文字文字列を取得
   */
  public String getEmojiString(String emojiName, String emojiId) {
    if (emojiName == null && emojiId == null) {
      return null;
    }

    if (emojiName != null && emojiId == null) {
      if (emojiName.startsWith(".")) {
        if (defaultEmojiId != null) {
          return "<:" + config.getBEDefaultEmojiName() + ":" + defaultEmojiId + "> ";
        } else {
          return "";
        }
      }
      return null;
    }

    if (emojiName != null && emojiId != null) {
      if (emojiName.isEmpty() || emojiId.isEmpty()) {
        return null;
      }

      if (defaultEmojiId != null && emojiId.equals(defaultEmojiId) && emojiName.startsWith(".")) {
        return "<:" + config.getBEDefaultEmojiName() + ":" + defaultEmojiId + ">";
      }
    }

    return "<:" + emojiName + ":" + emojiId + ">";
  }

  /**
   * ギルドを取得
   */
  private Guild getGuild() {
    long guildId = config.getDiscordGuildId();
    if (guildId == 0) {
      logger.warn("No Guild ID at config");
      return null;
    }

    Guild guild = jda.getGuildById(guildId);
    if (guild == null) {
      logger.warn("Couldn't find any guilds from your specific guild id: {}", guildId);
    }
    return guild;
  }

  /**
   * 画像をダウンロードして絵文字を作成
   */
  private CompletableFuture<String> downloadAndCreateEmoji(Guild guild, String emojiName, String imageUrl) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        URI uri = new URI(imageUrl);
        URL url = uri.toURL();

        BufferedImage bufferedImage = ImageIO.read(url);
        if (bufferedImage == null) {
          logger.error("Failed to read image: {}", imageUrl);
          return null;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bufferedImage, "png", baos);
        byte[] imageBytes = baos.toByteArray();

        return guild.createEmoji(emojiName, Icon.from(imageBytes))
            .submit()
            .thenApply(emoji -> {
              logger.info("Emoji is created: {}", emojiName);
              return emoji.getId();
            })
            .exceptionally(ex -> {
              logger.error("Failed to create emoji: {}", emojiName, ex);
              return null;
            })
            .join();

      } catch (Exception e) {
        logger.error("Failed to download the image: {}", imageUrl, e);
        return null;
      }
    });
  }
}
