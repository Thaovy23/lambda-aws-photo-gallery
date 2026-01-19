package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.json.JSONObject;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

public class LambdaGetObject
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private static final String VERIFY_TOKEN_FUNCTION_NAME = "LambdaVerifyToken";
  private final LambdaClient lambdaClient;

  public LambdaGetObject() {
    this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_1).build();
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent request, Context context) {

    LambdaLogger logger = context.getLogger();
    logger.log("LambdaGetObject: Processing request");

    if (isWarmUpRequest(request)) {
      return createWarmUpResponse();
    }

    // ========== AUTH CHECK ==========
    Map<String, String> requestHeaders = request.getHeaders();
    String userEmail = requestHeaders != null ? requestHeaders.get("x-user-email") : null;
    String userToken = requestHeaders != null ? requestHeaders.get("x-user-token") : null;

    if (userEmail == null || userEmail.isEmpty() || userToken == null || userToken.isEmpty()) {
      logger.log("Auth failed: Missing email or token");
      Map<String, String> errorHeaders = new java.util.HashMap<>();
      errorHeaders.put("Content-Type", "application/json");
      JSONObject errorJson = new JSONObject();
      errorJson.put("error", "Authentication required. Please provide email and token.");
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(401)
          .withBody(errorJson.toString())
          .withHeaders(errorHeaders);
    }

    try {
      // Invoke LambdaVerifyToken
      logger.log("Verifying token for email: " + userEmail);
      JSONObject verifyPayload = new JSONObject();
      verifyPayload.put("email", userEmail);
      verifyPayload.put("token", userToken);

      String verifyResult = invokeLambda(VERIFY_TOKEN_FUNCTION_NAME, verifyPayload.toString(), logger);
      JSONObject verifyResponse = new JSONObject(verifyResult);

      boolean isValid = verifyResponse.optBoolean("valid", false);
      if (!isValid) {
        logger.log("Auth failed: Invalid token for email: " + userEmail);
        Map<String, String> errorHeaders = new java.util.HashMap<>();
        errorHeaders.put("Content-Type", "application/json");
        JSONObject errorJson = new JSONObject();
        errorJson.put("error", "Invalid authentication token");
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(401)
            .withBody(errorJson.toString())
            .withHeaders(errorHeaders);
      }

      logger.log("Auth successful for email: " + userEmail);
    } catch (Exception e) {
      logger.log("Auth error: " + e.getMessage());
      Map<String, String> errorHeaders = new java.util.HashMap<>();
      errorHeaders.put("Content-Type", "application/json");
      JSONObject errorJson = new JSONObject();
      errorJson.put("error", "Authentication failed: " + e.getMessage());
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(401)
          .withBody(errorJson.toString())
          .withHeaders(errorHeaders);
    }
    // ========== END AUTH CHECK ==========

    String key = null;
    String bucketName = "public-mg2";
    String requestBody = request.getBody();

    if (requestBody != null && !requestBody.isEmpty()) {
      if (Boolean.TRUE.equals(request.getIsBase64Encoded())) {
        requestBody = new String(Base64.getDecoder().decode(requestBody));
      }
      try {
        JSONObject bodyJSON = new JSONObject(requestBody);
        key = bodyJSON.optString("key", null);
        String bucketParam = bodyJSON.optString("bucket", null);
        if (bucketParam != null && !bucketParam.isEmpty()) {
          bucketName = bucketParam;
        }
      } catch (Exception e) {
        // Use default values if parsing fails
      }
    }

    if (key == null) {
      Map<String, String> params = request.getQueryStringParameters();
      if (params != null) {
        key = params.get("key");
        String bucketParam = params.get("bucket");
        if (bucketParam != null && !bucketParam.isEmpty()) {
          bucketName = bucketParam;
        }
      }
    }

    if (key == null || key.isEmpty()) {
      context.getLogger().log("Missing key parameter in request");
      Map<String, String> errorHeaders = new java.util.HashMap<>();
      errorHeaders.put("Content-Type", "text/plain");
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(400)
          .withBody("Missing key parameter")
          .withHeaders(errorHeaders);
    }
    S3Client s3Client = S3Client.builder().region(Region.AP_SOUTHEAST_1).build();

    // Determine MIME type from file extension
    String mimeType = "application/octet-stream";
    String[] parts = key.split("\\.");
    if (parts.length > 1) {
      String extension = parts[parts.length - 1].toLowerCase();
      switch (extension) {
        case "png":
          mimeType = "image/png";
          break;
        case "jpg":
        case "jpeg":
          mimeType = "image/jpeg";
          break;
        case "html":
          mimeType = "text/html";
          break;
        default:
          mimeType = "application/octet-stream";
          break;
      }
    }

    // Try to get object directly from S3
    String encodedString = "";
    try {
      context.getLogger().log("Getting object from bucket: " + bucketName + ", key: " + key);
      GetObjectRequest s3Request = GetObjectRequest.builder().bucket(bucketName).key(key).build();
      byte[] buffer = new byte[10 * 1024 * 1024]; // 10Mb
      try (ResponseInputStream<GetObjectResponse> s3Response = s3Client.getObject(s3Request)) {
        buffer = s3Response.readAllBytes();
        encodedString = Base64.getEncoder().encodeToString(buffer);
        context.getLogger().log("Successfully retrieved object, size: " + buffer.length + " bytes");
      } catch (IOException ex) {
        context.getLogger().log("IOException: " + ex.getMessage());
        context.getLogger().log("Stack trace: " + java.util.Arrays.toString(ex.getStackTrace()));
        Map<String, String> errorHeaders = new java.util.HashMap<>();
        errorHeaders.put("Content-Type", "text/plain");
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(500)
            .withBody("Error reading object: " + ex.getMessage())
            .withHeaders(errorHeaders);
      }
    } catch (Exception e) {
      context.getLogger().log("Error getting object: " + e.getMessage());
      context.getLogger().log("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
      Map<String, String> errorHeaders = new java.util.HashMap<>();
      errorHeaders.put("Content-Type", "text/plain");
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(500)
          .withBody("Error getting object: " + e.getMessage())
          .withHeaders(errorHeaders);
    }
    
    // Wrap base64 data in JSON for Function URL compatibility
    JSONObject responseData = new JSONObject();
    responseData.put("data", encodedString);
    responseData.put("contentType", mimeType);
    
    Map<String, String> headers = new java.util.HashMap<>();
    headers.put("Content-Type", "application/json");
    
    APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
    response.setStatusCode(200);
    response.setBody(responseData.toString());
    response.setHeaders(headers);
    return response;
  }

  /**
   * Invoke another Lambda function
   */
  private String invokeLambda(String functionName, String payload, LambdaLogger logger) throws Exception {
    try {
      logger.log("Invoking Lambda: " + functionName + " with payload: " + payload);

      JSONObject apiGatewayEvent = new JSONObject();
      apiGatewayEvent.put("httpMethod", "POST");
      apiGatewayEvent.put("body", payload);
      apiGatewayEvent.put("isBase64Encoded", false);
      String wrappedPayload = apiGatewayEvent.toString();
      logger.log("Wrapped payload: " + wrappedPayload);

      InvokeRequest invokeRequest =
          InvokeRequest.builder()
              .functionName(functionName)
              .invocationType("RequestResponse")
              .payload(SdkBytes.fromUtf8String(wrappedPayload))
              .build();

      InvokeResponse invokeResponse = lambdaClient.invoke(invokeRequest);
      ByteBuffer responsePayload = invokeResponse.payload().asByteBuffer();
      String responseString = StandardCharsets.UTF_8.decode(responsePayload).toString();

      logger.log("Response from " + functionName + ": " + responseString);

      if (invokeResponse.functionError() != null) {
        logger.log("Lambda function error: " + invokeResponse.functionError());
        throw new Exception("Lambda function error: " + invokeResponse.functionError());
      }

      JSONObject responseJson = new JSONObject(responseString);
      return responseJson.optString("body", responseString);
    } catch (Exception e) {
      logger.log("Exception invoking " + functionName + ": " + e.getMessage());
      throw e;
    }
  }

  private static boolean isWarmUpRequest(APIGatewayProxyRequestEvent request) {
    if (request == null) {
      return false;
    }

    Map<String, String> headers = request.getHeaders();
    if (headers != null) {
      String warmUpHeader = headers.get("x-warm-up");
      if ("true".equalsIgnoreCase(warmUpHeader)) {
        return true;
      }
    }

    Map<String, String> params = request.getQueryStringParameters();
    if (params != null) {
      String warmUpParam = params.get("warmup");
      if ("true".equalsIgnoreCase(warmUpParam)) {
        return true;
      }
    }

    String body = request.getBody();
    if (body != null && !body.isEmpty()) {
      try {
        JSONObject json = new JSONObject(body);
        if (json.optBoolean("warmup", false)) {
          return true;
        }
        if ("aws.events".equals(json.optString("source"))) {
          return true;
        }
        if ("Scheduled Event".equals(json.optString("detail-type"))) {
          return true;
        }
      } catch (Exception e) {
        // Ignore invalid JSON
      }
    }

    return false;
  }

  private static APIGatewayProxyResponseEvent createWarmUpResponse() {
    JSONObject responseJson = new JSONObject();
    responseJson.put("message", "Warmed up");
    Map<String, String> headers = new java.util.HashMap<>();
    headers.put("Content-Type", "application/json");
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(200)
        .withBody(responseJson.toString())
        .withHeaders(headers);
  }

}
