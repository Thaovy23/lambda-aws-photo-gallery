package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

public class LambdaVerifyToken
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent request, Context context) {
    LambdaLogger logger = context.getLogger();
    logger.log("VerifyToken: Processing request");

    Map<String, String> headers = buildHeaders();

    if (isWarmUpRequest(request)) {
      return createWarmUpResponse(headers);
    }

    // Handle OPTIONS
    if ("OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(204)
          .withHeaders(headers);
    }

    JSONObject responseJson = new JSONObject();

    try {
      // Parse request body
      String requestBody = request.getBody();
      if (requestBody == null || requestBody.isEmpty()) {
        responseJson.put("error", "Request body is empty");
        return createErrorResponse(headers, responseJson, 400);
      }

      JSONObject body = new JSONObject(requestBody);
      String email = body.optString("email", null);
      String token = body.optString("token", null);

      // Validate inputs
      if (email == null || email.isEmpty()) {
        responseJson.put("error", "email is required");
        return createErrorResponse(headers, responseJson, 400);
      }

      if (token == null || token.isEmpty()) {
        responseJson.put("error", "token is required");
        return createErrorResponse(headers, responseJson, 400);
      }

      logger.log("Verifying token for email: " + email);

      // Get secret key from Parameter Store
      String secretKey = getParameterFromStore("keytokenhash", logger);
      if (secretKey == null) {
        responseJson.put("error", "Failed to retrieve secret key");
        return createErrorResponse(headers, responseJson, 500);
      }

      // Generate expected token from email
      String expectedToken = generateSecureToken(email, secretKey, logger);
      if (expectedToken == null) {
        responseJson.put("error", "Failed to generate token for verification");
        return createErrorResponse(headers, responseJson, 500);
      }

      // Compare tokens (constant-time comparison to prevent timing attacks)
      boolean isValid = constantTimeEquals(token, expectedToken);

      // Build response
      responseJson.put("valid", isValid);
      responseJson.put("email", email);

      if (isValid) {
        responseJson.put("message", "Token is valid");
        logger.log("✓ Token is VALID for email: " + email);
      } else {
        responseJson.put("message", "Token is invalid");
        logger.log("✗ Token is INVALID for email: " + email);
      }

      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withBody(responseJson.toString())
          .withHeaders(headers);

    } catch (Exception e) {
      logger.log("Exception in VerifyToken: " + e.getMessage());
      responseJson.put("error", "Failed to verify token: " + e.getMessage());
      return createErrorResponse(headers, responseJson, 500);
    }
  }

  /**
   * Generate secure token using HMAC-SHA256
   */
  private static String generateSecureToken(String data, String key, LambdaLogger logger) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      SecretKeySpec secretKeySpec =
          new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
      mac.init(secretKeySpec);

      byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(hmacBytes);

    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      logger.log("Error generating token: " + e.getMessage());
      return null;
    }
  }

  /**
   * Constant-time string comparison to prevent timing attacks
   */
  private static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }

    byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
    byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);

    if (aBytes.length != bBytes.length) {
      return false;
    }

    int result = 0;
    for (int i = 0; i < aBytes.length; i++) {
      result |= aBytes[i] ^ bBytes[i];
    }

    return result == 0;
  }

  /**
   * Retrieve parameter from AWS Systems Manager Parameter Store using Lambda Extension
   */
  private static String getParameterFromStore(String parameterName, LambdaLogger logger) {
    try {
      HttpClient client =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .followRedirects(HttpClient.Redirect.NORMAL)
              .connectTimeout(Duration.ofSeconds(10))
              .build();

      String sessionToken = System.getenv("AWS_SESSION_TOKEN");
      String encodedName = URLEncoder.encode(parameterName, StandardCharsets.UTF_8);

      // Try without leading slash first, then with leading slash if needed.
      String value = tryGetParameter(client, sessionToken, encodedName, logger);
      if (value != null) {
        logger.log("Successfully retrieved parameter: " + parameterName);
        return value;
      }

      String encodedNameWithSlash = URLEncoder.encode("/" + parameterName, StandardCharsets.UTF_8);
      value = tryGetParameter(client, sessionToken, encodedNameWithSlash, logger);
      if (value != null) {
        logger.log("Successfully retrieved parameter with leading slash: " + parameterName);
        return value;
      }

      return null;

    } catch (Exception e) {
      logger.log("Error retrieving parameter: " + e.getMessage());
      return null;
    }
  }

  private static String tryGetParameter(
      HttpClient client, String sessionToken, String encodedName, LambdaLogger logger)
      throws Exception {
    String url =
        "http://localhost:2773/systemsmanager/parameters/get/?name="
            + encodedName
            + "&withDecryption=true";

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .header("X-Aws-Parameters-Secrets-Token", sessionToken)
            .GET()
            .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    logger.log("Parameter Store response status: " + response.statusCode());
    logger.log("Parameter Store response body: " + response.body());

    if (response.statusCode() >= 200 && response.statusCode() < 300) {
      JSONObject jsonBody = new JSONObject(response.body());
      JSONObject parameter = jsonBody.getJSONObject("Parameter");
      return parameter.getString("Value");
    }

    return null;
  }

  private static Map<String, String> buildHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    return headers;
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

  private static APIGatewayProxyResponseEvent createErrorResponse(
      Map<String, String> headers, JSONObject responseJson, int statusCode) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withBody(responseJson.toString())
        .withHeaders(headers);
  }
}
