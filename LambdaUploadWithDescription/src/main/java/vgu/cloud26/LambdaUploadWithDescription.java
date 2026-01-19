package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

public class LambdaUploadWithDescription
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private static final String VERIFY_TOKEN_FUNCTION_NAME = "LambdaVerifyToken";
  private static final String DEFAULT_UPLOAD_FUNCTION_NAME = "LambdaUploadObject";
  private static final String DEFAULT_INSERT_FUNCTION_NAME = "LambdaInsertPhoto";
  private static final String DEFAULT_RESIZE_FUNCTION_NAME = "LamdaResizer";
  private final LambdaClient lambdaClient;
  private final String uploadFunctionName;
  private final String insertFunctionName;
  private final String resizeFunctionName;

  public LambdaUploadWithDescription() {
    this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_1).build();
    this.uploadFunctionName =
        System.getenv().getOrDefault("UPLOAD_FUNCTION_NAME", DEFAULT_UPLOAD_FUNCTION_NAME);
    this.insertFunctionName =
        System.getenv().getOrDefault("INSERT_FUNCTION_NAME", DEFAULT_INSERT_FUNCTION_NAME);
    this.resizeFunctionName =
        System.getenv().getOrDefault("RESIZE_FUNCTION_NAME", DEFAULT_RESIZE_FUNCTION_NAME);
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent request, Context context) {
    LambdaLogger logger = context.getLogger();
    logger.log("LambdaUploadWithDescription: Processing upload request");

    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");

    if (isWarmUpRequest(request)) {
      return createWarmUpResponse(headers);
    }

    // Handle OPTIONS preflight request
    if ("OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(204)
          .withHeaders(headers);
    }

    JSONObject responseJson = new JSONObject();
    JSONObject results = new JSONObject();

    try {
      // ========== AUTH CHECK ==========
      // Extract email and token from headers
      Map<String, String> requestHeaders = request.getHeaders();
      String userEmail = requestHeaders != null ? requestHeaders.get("x-user-email") : null;
      String userToken = requestHeaders != null ? requestHeaders.get("x-user-token") : null;

      if (userEmail == null || userEmail.isEmpty() || userToken == null || userToken.isEmpty()) {
        logger.log("Auth failed: Missing email or token");
        responseJson.put("error", "Authentication required. Please provide email and token.");
        return createErrorResponse(headers, responseJson, 401);
      }

      // Invoke LambdaVerifyToken to verify credentials
      logger.log("Verifying token for email: " + userEmail);
      JSONObject verifyPayload = new JSONObject();
      verifyPayload.put("email", userEmail);
      verifyPayload.put("token", userToken);

      String verifyResult = invokeLambda(VERIFY_TOKEN_FUNCTION_NAME, verifyPayload.toString(), logger);
      JSONObject verifyResponse = new JSONObject(verifyResult);

      // Check if token is valid
      boolean isValid = verifyResponse.optBoolean("valid", false);
      if (!isValid) {
        logger.log("Auth failed: Invalid token for email: " + userEmail);
        responseJson.put("error", "Invalid authentication token");
        return createErrorResponse(headers, responseJson, 401);
      }

      logger.log("Auth successful for email: " + userEmail);
      // ========== END AUTH CHECK ==========

      // Parse request body
      String requestBody = request.getBody();

      if (requestBody != null && Boolean.TRUE.equals(request.getIsBase64Encoded())) {
        logger.log("Decoding base64 body");
        byte[] decodedBytes = Base64.getDecoder().decode(requestBody);
        requestBody = new String(decodedBytes, StandardCharsets.UTF_8);
      }

      if (requestBody == null || requestBody.isEmpty()) {
        responseJson.put("error", "Request body is empty");
        return createErrorResponse(headers, responseJson, 400);
      }

      JSONObject body = new JSONObject(requestBody);
      // Accept both "key" and "filename" for backward compatibility
      String originalFilename = body.optString("key", null);
      if (originalFilename == null || originalFilename.isEmpty()) {
        originalFilename = body.optString("filename", null);
      }
      String description = body.optString("description", null);
      String content = body.optString("content", null);

      // Validate required fields
      if (originalFilename == null || originalFilename.isEmpty()) {
        responseJson.put("error", "key or filename is required");
        return createErrorResponse(headers, responseJson, 400);
      }

      if (description == null || description.isEmpty()) {
        responseJson.put("error", "description is required");
        return createErrorResponse(headers, responseJson, 400);
      }

      if (content == null || content.isEmpty()) {
        responseJson.put("error", "content is required");
        return createErrorResponse(headers, responseJson, 400);
      }

      String key = hashFilename(originalFilename);
      logger.log("Processing upload for key: " + key);

      try {
        byte[] fileBytes = Base64.getDecoder().decode(content);
        logger.log("Decoded file content, size: " + fileBytes.length + " bytes");
      } catch (IllegalArgumentException e) {
        JSONObject errorResult = new JSONObject();
        errorResult.put("success", false);
        errorResult.put("error", "Invalid base64 content: " + e.getMessage());
        results.put("rds_insert", errorResult);
        results.put("original_upload", errorResult);
        results.put("resized_upload", errorResult);
        responseJson.put("results", results);
        return createErrorResponse(headers, responseJson, 400);
      }

      JSONObject insertPayload = new JSONObject();
      insertPayload.put("key", key);
      insertPayload.put("description", description);
      insertPayload.put("email", userEmail);

      JSONObject uploadPayload = new JSONObject();
      uploadPayload.put("key", key);
      uploadPayload.put("content", content);

      JSONObject resizePayload = new JSONObject();
      resizePayload.put("key", key);
      resizePayload.put("content", content);

      CompletableFuture<JSONObject> insertFuture =
          CompletableFuture.supplyAsync(
              () -> invokeLambdaToJson(insertFunctionName, insertPayload, logger));
      CompletableFuture<JSONObject> uploadFuture =
          CompletableFuture.supplyAsync(
              () -> invokeLambdaToJson(uploadFunctionName, uploadPayload, logger));
      CompletableFuture<JSONObject> resizeFuture =
          CompletableFuture.supplyAsync(
              () -> invokeLambdaToJson(resizeFunctionName, resizePayload, logger));

      JSONObject rdsResult = insertFuture.join();
      JSONObject originalUploadResult = uploadFuture.join();
      JSONObject resizedUploadResult = resizeFuture.join();

      results.put("rds_insert", rdsResult);
      results.put("original_upload", originalUploadResult);
      results.put("resized_upload", resizedUploadResult);

      responseJson.put("key", key);
      responseJson.put("results", results);

      // Determine overall status
      boolean allSuccess =
          isSuccessResult(rdsResult)
              && isSuccessResult(originalUploadResult)
              && isSuccessResult(resizedUploadResult);

      if (allSuccess) {
        responseJson.put("message", "All activities completed successfully");
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withBody(responseJson.toString())
            .withHeaders(headers);
      } else {
        responseJson.put("message", "Some activities failed. Check results for details.");
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(207) // Multi-Status
            .withBody(responseJson.toString())
            .withHeaders(headers);
      }

    } catch (Exception e) {
      logger.log("Exception in LambdaUploadWithDescription: " + e);
      logger.log("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
      responseJson.put("error", "Failed to process upload: " + e.getMessage());
      return createErrorResponse(headers, responseJson, 500);
    }
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

      // Check if Lambda invocation failed
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

  private JSONObject invokeLambdaToJson(String functionName, JSONObject payload, LambdaLogger logger) {
    try {
      String result = invokeLambda(functionName, payload.toString(), logger);
      return parseResultJson(result);
    } catch (Exception e) {
      JSONObject error = new JSONObject();
      error.put("success", false);
      error.put("error", e.getMessage());
      return error;
    }
  }

  private JSONObject parseResultJson(String result) {
    try {
      return new JSONObject(result);
    } catch (Exception e) {
      JSONObject wrapper = new JSONObject();
      wrapper.put("raw", result);
      return wrapper;
    }
  }

  private boolean isSuccessResult(JSONObject result) {
    if (result.optBoolean("success", false)) {
      return true;
    }
    if (result.has("statusCode")) {
      int statusCode = result.optInt("statusCode", 500);
      return statusCode >= 200 && statusCode < 300;
    }
    return !result.has("error");
  }

  private static APIGatewayProxyResponseEvent createErrorResponse(
      Map<String, String> headers, JSONObject responseJson, int statusCode) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withBody(responseJson.toString())
        .withHeaders(headers);
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

  private static APIGatewayProxyResponseEvent createWarmUpResponse(
      Map<String, String> headers) {
    JSONObject responseJson = new JSONObject();
    responseJson.put("message", "Warmed up");
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(200)
        .withBody(responseJson.toString())
        .withHeaders(headers);
  }

  private static String hashFilename(String filename) {
    String extension = "";
    String nameWithoutExt = filename;
    int lastDotIndex = filename.lastIndexOf('.');
    if (lastDotIndex > 0 && lastDotIndex < filename.length() - 1) {
      extension = filename.substring(lastDotIndex);
      nameWithoutExt = filename.substring(0, lastDotIndex);
    }

    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashBytes = digest.digest(nameWithoutExt.getBytes(StandardCharsets.UTF_8));
      StringBuilder hexString = new StringBuilder();
      for (byte b : hashBytes) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString + extension;
    } catch (NoSuchAlgorithmException e) {
      return filename;
    }
  }
}
