package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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

public class LambdaDeletePhoto
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private static final String DEFAULT_DELETE_OBJECT_FUNCTION = "LambdaDeleteObject";
  private static final String DEFAULT_DELETE_DB_FUNCTION = "LambdaDeletePhotoDB";
  private static final String VERIFY_TOKEN_FUNCTION_NAME = "LambdaVerifyToken";

  private final LambdaClient lambdaClient;
  private final String deleteObjectFunctionName;
  private final String deleteDbFunctionName;

  public LambdaDeletePhoto() {
    this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_1).build();
    this.deleteObjectFunctionName =
        System.getenv().getOrDefault("DELETE_OBJECT_FUNCTION_NAME", DEFAULT_DELETE_OBJECT_FUNCTION);
    this.deleteDbFunctionName =
        System.getenv().getOrDefault("DELETE_DB_FUNCTION_NAME", DEFAULT_DELETE_DB_FUNCTION);
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent request, Context context) {
    LambdaLogger logger = context.getLogger();
    // Rely on Function URL CORS. Only set minimal headers.
    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    JSONObject responseJson = new JSONObject();
    
    try {
      // Log full request details for debugging
      logger.log("=== Full Request Debug Info ===");
      logger.log("DeletePhoto orchestrator request - Method: " + request.getHttpMethod() + ", Body: " + request.getBody());
      logger.log("Request path: " + request.getPath());
      logger.log("Request pathParameters: " + request.getPathParameters());
      logger.log("Request queryStringParameters: " + request.getQueryStringParameters());
      logger.log("Request headers: " + request.getHeaders());
      logger.log("Request resource: " + request.getResource());
      logger.log("Request requestContext: " + request.getRequestContext());
      logger.log("Request isBase64Encoded: " + request.getIsBase64Encoded());
      logger.log("Request multiValueHeaders: " + request.getMultiValueHeaders());
      logger.log("Request multiValueQueryStringParameters: " + request.getMultiValueQueryStringParameters());
      
      // Try to serialize entire request object to see actual format
      try {
        JSONObject requestJson = new JSONObject();
        requestJson.put("httpMethod", request.getHttpMethod());
        requestJson.put("body", request.getBody());
        requestJson.put("path", request.getPath());
        requestJson.put("pathParameters", request.getPathParameters());
        requestJson.put("queryStringParameters", request.getQueryStringParameters());
        requestJson.put("headers", request.getHeaders());
        requestJson.put("isBase64Encoded", request.getIsBase64Encoded());
        logger.log("Serialized request object: " + requestJson.toString());
      } catch (Exception e) {
        logger.log("Error serializing request: " + e.getMessage());
      }
      
      logger.log("=== End Request Debug Info ===");
      
      // Handle null method (direct Lambda invocation or different event format)
      String httpMethod = request.getHttpMethod();
      if (httpMethod == null) {
        logger.log("HTTP Method is null - might be direct Lambda invocation");
        // Try to extract from headers or other sources
        Map<String, String> reqHeaders = request.getHeaders();
        if (reqHeaders != null) {
          httpMethod = reqHeaders.get("x-http-method");
          if (httpMethod == null) {
            httpMethod = reqHeaders.get("X-Http-Method");
          }
        }
        logger.log("Extracted HTTP Method from headers: " + httpMethod);
      }

      // Handle OPTIONS preflight request - check both method and headers
      boolean isOptionsRequest = false;
      if (httpMethod != null && "OPTIONS".equalsIgnoreCase(httpMethod)) {
        isOptionsRequest = true;
      } else {
        // Also check headers for OPTIONS request
        Map<String, String> reqHeaders = request.getHeaders();
        if (reqHeaders != null) {
          String accessControlRequestMethod = reqHeaders.get("access-control-request-method");
          if (accessControlRequestMethod != null || "OPTIONS".equalsIgnoreCase(reqHeaders.get("x-http-method"))) {
            isOptionsRequest = true;
          }
        }
      }
      
      if (isOptionsRequest) {
        logger.log("Handling OPTIONS preflight request");
        APIGatewayProxyResponseEvent optionsResponse = new APIGatewayProxyResponseEvent()
            .withStatusCode(200)  // Use 200 for compatibility
            .withHeaders(headers)
            .withBody("");  // Empty body for OPTIONS
        logger.log("OPTIONS response headers: " + headers);
        return optionsResponse;
      }

      // ========== AUTH CHECK ==========
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

      // Try to get key from multiple sources (body, query params, path params)
      String s3Key = null;
      Integer id = null;
      
      // Method 1: Try to get from request body
      String requestBody = request.getBody();
      if (requestBody != null && !requestBody.isEmpty()) {
        try {
          // Check if body is base64 encoded
          if (Boolean.TRUE.equals(request.getIsBase64Encoded())) {
            requestBody = new String(Base64.getDecoder().decode(requestBody), StandardCharsets.UTF_8);
            logger.log("Decoded base64 body: " + requestBody);
          }
          
          JSONObject body = new JSONObject(requestBody);
          s3Key = body.optString("s3_key", null);
          if (s3Key == null || s3Key.isEmpty()) {
            s3Key = body.optString("key", null);
          }
          if (body.has("id") && !body.isNull("id")) {
            id = body.optInt("id");
          }
          logger.log("Parsed from body - s3Key: " + s3Key + ", id: " + id);
        } catch (Exception e) {
          logger.log("Error parsing request body: " + e.getMessage());
        }
      }
      
      // Method 2: Try to get from query parameters (for Lambda Function URL compatibility)
      if ((s3Key == null || s3Key.isEmpty()) && id == null) {
        Map<String, String> queryParams = request.getQueryStringParameters();
        logger.log("Query params: " + queryParams);
        if (queryParams != null && !queryParams.isEmpty()) {
          s3Key = queryParams.get("key");
          if (s3Key == null || s3Key.isEmpty()) {
            s3Key = queryParams.get("s3_key");
          }
          String idStr = queryParams.get("id");
          if (idStr != null && !idStr.isEmpty()) {
            try {
              id = Integer.parseInt(idStr);
            } catch (NumberFormatException e) {
              logger.log("Invalid id in query params: " + idStr);
            }
          }
          logger.log("Parsed from query params - s3Key: " + s3Key + ", id: " + id);
        } else {
          logger.log("Query params is null or empty");
        }
      }
      
      // Method 2b: Try to extract from path if it contains the key
      if ((s3Key == null || s3Key.isEmpty()) && id == null) {
        String path = request.getPath();
        logger.log("Request path: " + path);
        // Path might be like /?key=value or /key=value
        if (path != null && path.contains("key=")) {
          int keyIndex = path.indexOf("key=");
          String remaining = path.substring(keyIndex + 4);
          int endIndex = remaining.indexOf("&");
          if (endIndex > 0) {
            s3Key = remaining.substring(0, endIndex);
          } else {
            s3Key = remaining;
          }
          logger.log("Extracted key from path: " + s3Key);
        }
      }
      
      // Method 3: Try to get from path parameters
      if ((s3Key == null || s3Key.isEmpty()) && id == null) {
        Map<String, String> pathParams = request.getPathParameters();
        if (pathParams != null) {
          s3Key = pathParams.get("key");
          if (s3Key == null || s3Key.isEmpty()) {
            s3Key = pathParams.get("s3_key");
          }
          logger.log("Parsed from path params - s3Key: " + s3Key);
        }
      }

      if ((s3Key == null || s3Key.isEmpty()) && id == null) {
        logger.log("No key or id found in body, query params, or path params");
        responseJson.put("error", "Either s3_key/key or id is required in body, query parameters, or path parameters");
        return createErrorResponse(headers, responseJson, 400);
      }

      // Step 1 + Step 2: Delete from S3 and DB in parallel
      logger.log("Deleting from S3 and RDS in parallel");
      JSONObject deleteObjectPayload = new JSONObject();
      deleteObjectPayload.put("key", s3Key);

      JSONObject deleteDbPayload = new JSONObject();
      if (s3Key != null && !s3Key.isEmpty()) {
        deleteDbPayload.put("s3_key", s3Key);
      }
      if (id != null) {
        deleteDbPayload.put("id", id);
      }

      CompletableFuture<String> deleteObjectFuture = CompletableFuture.supplyAsync(() -> {
        try {
          String result =
              invokeLambda(deleteObjectFunctionName, deleteObjectPayload.toString(), logger);
          logger.log("S3 delete result: " + result);
          return result;
        } catch (Exception e) {
          logger.log("Error invoking " + deleteObjectFunctionName + ": " + e.getMessage());
          return "{\"error\":\"" + e.getMessage() + "\"}";
        }
      });

      CompletableFuture<String> deleteDbFuture = CompletableFuture.supplyAsync(() -> {
        try {
          String result = invokeLambda(deleteDbFunctionName, deleteDbPayload.toString(), logger);
          logger.log("RDS delete result: " + result);
          return result;
        } catch (Exception e) {
          logger.log("Error invoking " + deleteDbFunctionName + ": " + e.getMessage());
          return "{\"error\":\"" + e.getMessage() + "\"}";
        }
      });

      String deleteObjectResult = deleteObjectFuture.join();
      String deleteDbResult = deleteDbFuture.join();

      responseJson.put("message", "Delete orchestrated successfully - Images and database record deleted");
      responseJson.put("s3_key", s3Key);
      responseJson.put("delete_order", new JSONObject()
          .put("step1", "Delete original and resized images from S3")
          .put("step2", "Delete record from RDS database"));
      responseJson.put("delete_object_response", deleteObjectResult);
      responseJson.put("delete_db_response", deleteDbResult);

      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withBody(responseJson.toString())
          .withHeaders(headers);
    } catch (Exception e) {
      logger.log("Exception in delete orchestrator: " + e);
      logger.log("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
      responseJson.put("error", "Failed to orchestrate delete: " + e.getMessage());
      return createErrorResponse(headers, responseJson, 500);
    }
  }

  private String invokeLambda(String functionName, String payload, LambdaLogger logger)
      throws Exception {
    try {
      logger.log("Invoking Lambda: " + functionName + " with payload: " + payload);

      // Wrap payload to mimic APIGatewayProxyRequestEvent structure so downstream Lambdas can read body
      JSONObject wrapper = new JSONObject();
      wrapper.put("httpMethod", "POST");
      wrapper.put("body", payload);
      wrapper.put("isBase64Encoded", false);
      String wrappedPayload = wrapper.toString();

      InvokeRequest request =
          InvokeRequest.builder()
              .functionName(functionName)
              .invocationType("RequestResponse")
              .payload(SdkBytes.fromUtf8String(wrappedPayload))
              .build();

      InvokeResponse response = lambdaClient.invoke(request);
      ByteBuffer responsePayload = response.payload().asByteBuffer();
      String responseString = StandardCharsets.UTF_8.decode(responsePayload).toString();
      logger.log("Response from " + functionName + ": " + responseString);

      // Check if Lambda invocation failed
      if (response.functionError() != null) {
        logger.log("Lambda function error: " + response.functionError());
        throw new Exception("Lambda function error: " + response.functionError() + " - " + responseString);
      }

      JSONObject responseJson = new JSONObject(responseString);
      return responseJson.optString("body", responseString);
    } catch (Exception e) {
      logger.log("Exception invoking " + functionName + ": " + e.getMessage());
      throw e;
    }
  }

  private static APIGatewayProxyResponseEvent createErrorResponse(
      Map<String, String> headers, JSONObject responseJson, int statusCode) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withBody(responseJson.toString())
        .withHeaders(headers);
  }
}

