package net.kishax.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsWorkerTest {

  @Mock
  private SqsClient sqsClient;

  @Mock
  private RedisClient redisClient;

  @Mock
  private DatabaseClient databaseClient;

  private SqsWorker sqsWorker;
  private final String testQueueUrl = "https://sqs.ap-northeast-1.amazonaws.com/123456789012/test-queue";
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    WebToMcMessageSender webToMcSender = mock(WebToMcMessageSender.class);
    sqsWorker = new SqsWorker(sqsClient, testQueueUrl, redisClient, databaseClient, webToMcSender);
  }

  @Test
  void testSqsWorkerCreation() {
    assertNotNull(sqsWorker);
  }

  @Test
  void testStartAndStop() {
    // Test that start and stop don't throw exceptions
    assertDoesNotThrow(() -> {
      sqsWorker.start();
      Thread.sleep(100); // Give it a moment to start
      sqsWorker.stop();
    });
  }

  @Test
  void testAuthTokenMessageProcessing() throws Exception {
    // Mock SQS response with auth token message
    String authTokenJson = """
        {
            "type": "auth_token",
            "mcid": "testPlayer",
            "uuid": "550e8400-e29b-41d4-a716-446655440000",
            "authToken": "test-auth-token-123",
            "expiresAt": 1234567890000
        }
        """;

    Message mockMessage = Message.builder()
        .messageId("test-message-id")
        .body(authTokenJson)
        .receiptHandle("test-receipt-handle")
        .build();

    ReceiveMessageResponse mockResponse = ReceiveMessageResponse.builder()
        .messages(Collections.singletonList(mockMessage))
        .build();

    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
        .thenReturn(mockResponse);

    when(sqsClient.deleteMessage(any(DeleteMessageRequest.class)))
        .thenReturn(DeleteMessageResponse.builder().build());

    // Start worker briefly to process the message
    sqsWorker.start();
    Thread.sleep(200); // Give it time to process
    sqsWorker.stop();

    // Verify database client was called
    verify(databaseClient, atLeastOnce()).upsertMinecraftPlayer(
        eq("testPlayer"),
        eq("550e8400-e29b-41d4-a716-446655440000"),
        eq("test-auth-token-123"),
        eq(Instant.ofEpochMilli(1234567890000L)));

    // Verify message was deleted
    verify(sqsClient, atLeastOnce()).deleteMessage(any(DeleteMessageRequest.class));
  }

  @Test
  void testOtpResponseMessageProcessing() throws Exception {
    // Mock SQS response with OTP response message
    String otpResponseJson = """
        {
            "type": "mc_otp_response",
            "mcid": "testPlayer",
            "uuid": "550e8400-e29b-41d4-a716-446655440000",
            "success": true,
            "message": "OTP verified successfully",
            "timestamp": 1234567890000
        }
        """;

    Message mockMessage = Message.builder()
        .messageId("test-message-id")
        .body(otpResponseJson)
        .receiptHandle("test-receipt-handle")
        .build();

    ReceiveMessageResponse mockResponse = ReceiveMessageResponse.builder()
        .messages(Collections.singletonList(mockMessage))
        .build();

    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
        .thenReturn(mockResponse);

    when(sqsClient.deleteMessage(any(DeleteMessageRequest.class)))
        .thenReturn(DeleteMessageResponse.builder().build());

    // Start worker briefly to process the message
    sqsWorker.start();
    Thread.sleep(200); // Give it time to process
    sqsWorker.stop();

    // Verify Redis client was called
    verify(redisClient, atLeastOnce()).setWithTtl(
        eq("otp_response:testPlayer_550e8400-e29b-41d4-a716-446655440000"),
        any(SqsWorker.OtpResponse.class),
        eq(300L));

    verify(redisClient, atLeastOnce()).publish(
        eq("otp_response:testPlayer_550e8400-e29b-41d4-a716-446655440000"),
        any(SqsWorker.OtpResponse.class));

    // Verify message was deleted
    verify(sqsClient, atLeastOnce()).deleteMessage(any(DeleteMessageRequest.class));
  }

  @Test
  void testUnknownMessageType() throws Exception {
    // Mock SQS response with unknown message type
    String unknownJson = """
        {
            "type": "unknown_message_type",
            "data": "some data"
        }
        """;

    Message mockMessage = Message.builder()
        .messageId("test-message-id")
        .body(unknownJson)
        .receiptHandle("test-receipt-handle")
        .build();

    ReceiveMessageResponse mockResponse = ReceiveMessageResponse.builder()
        .messages(Collections.singletonList(mockMessage))
        .build();

    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
        .thenReturn(mockResponse);

    when(sqsClient.deleteMessage(any(DeleteMessageRequest.class)))
        .thenReturn(DeleteMessageResponse.builder().build());

    // Start worker briefly to process the message
    sqsWorker.start();
    Thread.sleep(200); // Give it time to process
    sqsWorker.stop();

    // Verify message was still deleted (to prevent reprocessing)
    verify(sqsClient, atLeastOnce()).deleteMessage(any(DeleteMessageRequest.class));

    // Verify no database or Redis operations were performed
    verifyNoInteractions(databaseClient);
    verifyNoInteractions(redisClient);
  }

  @Test
  void testEmptyMessageBody() throws Exception {
    // Mock SQS response with empty message body
    Message mockMessage = Message.builder()
        .messageId("test-message-id")
        .body("")
        .receiptHandle("test-receipt-handle")
        .build();

    ReceiveMessageResponse mockResponse = ReceiveMessageResponse.builder()
        .messages(Collections.singletonList(mockMessage))
        .build();

    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
        .thenReturn(mockResponse);

    // Start worker briefly to process the message
    sqsWorker.start();
    Thread.sleep(200); // Give it time to process
    sqsWorker.stop();

    // Verify no database or Redis operations were performed
    verifyNoInteractions(databaseClient);
    verifyNoInteractions(redisClient);

    // Verify message was not deleted (because it wasn't processed)
    verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
  }

  @Test
  void testNoMessages() throws Exception {
    // Mock SQS response with no messages
    ReceiveMessageResponse mockResponse = ReceiveMessageResponse.builder()
        .messages(Collections.emptyList())
        .build();

    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
        .thenReturn(mockResponse);

    // Start worker briefly
    sqsWorker.start();
    Thread.sleep(200);
    sqsWorker.stop();

    // Verify no operations were performed
    verifyNoInteractions(databaseClient);
    verifyNoInteractions(redisClient);
    verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
  }
}
