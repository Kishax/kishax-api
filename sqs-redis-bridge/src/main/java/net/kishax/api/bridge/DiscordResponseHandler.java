package net.kishax.api.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Discord応答ハンドラー
 * discord-botからのRedis応答をSQS経由でWEB/MCに送信
 */
public class DiscordResponseHandler {
  private static final Logger logger = LoggerFactory.getLogger(DiscordResponseHandler.class);
  private static final String DISCORD_RESPONSES_CHANNEL = "discord_responses";

  private final RedisClient redisClient;
  private final McToWebMessageSender mcToWebSender;
  private final WebToMcMessageSender webToMcSender;
  private final ObjectMapper objectMapper;

  public DiscordResponseHandler(RedisClient redisClient,
      McToWebMessageSender mcToWebSender,
      WebToMcMessageSender webToMcSender) {
    this.redisClient = redisClient;
    this.mcToWebSender = mcToWebSender;
    this.webToMcSender = webToMcSender;
    this.objectMapper = new ObjectMapper();
  }

  /**
   * Discord応答の購読を開始
   */
  public void startSubscription() {
    CompletableFuture.runAsync(() -> {
      try {
        logger.info("Subscribing discord response...");
        redisClient.subscribe(DISCORD_RESPONSES_CHANNEL, this::handleDiscordResponse);
      } catch (Exception e) {
        logger.error("Error occurred while subscribing discord response: ", e);
      }
    });
  }

  /**
   * discord-botからの応答処理
   */
  private void handleDiscordResponse(String responseJson) {
    try {
      JsonNode responseNode = objectMapper.readTree(responseJson);
      String responseType = responseNode.path("type").asText();
      String result = responseNode.path("result").asText();
      JsonNode data = responseNode.path("data");
      String originalSource = data.path("source").asText("unknown");

      logger.debug("Received the response of discord: {} - {}", responseType, result);

      // 応答メッセージを構築
      Map<String, Object> responseMessage = new HashMap<>();
      responseMessage.put("type", "discord_response");
      responseMessage.put("original_type", responseType);
      responseMessage.put("result", result);
      responseMessage.put("timestamp", System.currentTimeMillis());

      // データがある場合は含める
      if (data != null && !data.isNull()) {
        responseMessage.put("data", objectMapper.convertValue(data, Map.class));
      }

      objectMapper.writeValueAsString(responseMessage);

      // 送信元に応じてSQS経路を選択
      if ("MC".equalsIgnoreCase(originalSource)) {
        // MC向け応答
        webToMcSender.sendGenericMessage("discord_response", responseMessage);
        logger.debug("Sent the response of discord to mc");
      } else if ("WEB".equalsIgnoreCase(originalSource)) {
        // WEB向け応答
        mcToWebSender.sendGenericMessage("discord_response", responseMessage);
        logger.debug("Sent the response of discord to web");
      } else {
        // 不明な送信元の場合は両方に送信
        webToMcSender.sendGenericMessage("discord_response", responseMessage);
        mcToWebSender.sendGenericMessage("discord_response", responseMessage);
        logger.debug("Sent the response of discord at both web and mc (Unsupported delivery point: {})",
            originalSource);
      }

    } catch (Exception e) {
      logger.error("An error occurred while processing discord request/response", e);
    }
  }

  /**
   * エラー応答を生成して送信
   */
  public void sendErrorResponse(String originalType, String errorMessage, String source) {
    try {
      Map<String, Object> errorResponse = new HashMap<>();
      errorResponse.put("type", "discord_response");
      errorResponse.put("original_type", originalType);
      errorResponse.put("result", "error");
      errorResponse.put("error_message", errorMessage);
      errorResponse.put("timestamp", System.currentTimeMillis());

      if ("MC".equalsIgnoreCase(source)) {
        webToMcSender.sendGenericMessage("discord_error", errorResponse);
      } else if ("WEB".equalsIgnoreCase(source)) {
        mcToWebSender.sendGenericMessage("discord_error", errorResponse);
      } else {
        webToMcSender.sendGenericMessage("discord_error", errorResponse);
        mcToWebSender.sendGenericMessage("discord_error", errorResponse);
      }

      logger.warn("Sent the error response: {}", errorMessage);
    } catch (Exception e) {
      logger.error("An error occurred while sending the error response", e);
    }
  }

  /**
   * 成功応答を生成して送信
   */
  public void sendSuccessResponse(String originalType, Map<String, Object> data, String source) {
    try {
      Map<String, Object> successResponse = new HashMap<>();
      successResponse.put("type", "discord_response");
      successResponse.put("original_type", originalType);
      successResponse.put("result", "success");
      successResponse.put("data", data);
      successResponse.put("timestamp", System.currentTimeMillis());

      if ("MC".equalsIgnoreCase(source)) {
        webToMcSender.sendGenericMessage("discord_success", successResponse);
      } else if ("WEB".equalsIgnoreCase(source)) {
        mcToWebSender.sendGenericMessage("discord_success", successResponse);
      } else {
        webToMcSender.sendGenericMessage("discord_success", successResponse);
        mcToWebSender.sendGenericMessage("discord_success", successResponse);
      }

      logger.debug("Sent the success response: {}", originalType);
    } catch (Exception e) {
      logger.error("An error occurred while sending the success response", e);
    }
  }
}
