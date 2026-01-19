package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

public class LambdaInsertPhoto
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private static final String RDS_INSTANCE_HOSTNAME =
      "mydatabase.cliyoi48e7ab.ap-southeast-1.rds.amazonaws.com";
  private static final int RDS_INSTANCE_PORT = 3306;
  private static final String DB_USER = "cloud26";
  private static final String JDBC_URL =
      "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT + "/Cloud26";

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent request, Context context) {
    LambdaLogger logger = context.getLogger();

    if (isWarmUpRequest(request)) {
      return createWarmUpResponse();
    }

    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    JSONObject responseJson = new JSONObject();

    try {
      String requestBody = request.getBody();
      if (requestBody != null && Boolean.TRUE.equals(request.getIsBase64Encoded())) {
        byte[] decodedBytes = Base64.getDecoder().decode(requestBody);
        requestBody = new String(decodedBytes, StandardCharsets.UTF_8);
      }

      if (requestBody == null || requestBody.isEmpty()) {
        responseJson.put("success", false);
        responseJson.put("error", "Request body is empty");
        return createErrorResponse(headers, responseJson, 400);
      }

      JSONObject body = new JSONObject(requestBody);
      String key = body.optString("key", null);
      if (key == null || key.isEmpty()) {
        key = body.optString("filename", null);
      }
      String description = body.optString("description", null);
      String email = body.optString("email", null);
      if (email == null || email.isEmpty()) {
        email = body.optString("userEmail", null);
      }

      if (key == null || key.isEmpty()) {
        responseJson.put("success", false);
        responseJson.put("error", "key or filename is required");
        return createErrorResponse(headers, responseJson, 400);
      }
      if (description == null || description.isEmpty()) {
        responseJson.put("success", false);
        responseJson.put("error", "description is required");
        return createErrorResponse(headers, responseJson, 400);
      }
      if (email == null || email.isEmpty()) {
        responseJson.put("success", false);
        responseJson.put("error", "email is required");
        return createErrorResponse(headers, responseJson, 400);
      }

      Class.forName("com.mysql.cj.jdbc.Driver");
      int rowsAffected;
      try (Connection connection =
          DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());
          PreparedStatement st =
              connection.prepareStatement(
                  "INSERT INTO Photos (S3Key, Description, email) VALUES (?, ?, ?)")) {
        st.setString(1, key);
        st.setString(2, description);
        st.setString(3, email);
        rowsAffected = st.executeUpdate();
      }

      responseJson.put("success", true);
      responseJson.put("message", "Photo inserted successfully into RDS");
      responseJson.put("rows_affected", rowsAffected);
      responseJson.put("key", key);
      responseJson.put("email", email);
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withBody(responseJson.toString())
          .withHeaders(headers);
    } catch (ClassNotFoundException e) {
      logger.log("Database driver not found: " + e.getMessage());
      responseJson.put("success", false);
      responseJson.put("error", "Database driver not found: " + e.getMessage());
      return createErrorResponse(headers, responseJson, 500);
    } catch (Exception e) {
      logger.log("Insert failed: " + e.getMessage());
      responseJson.put("success", false);
      responseJson.put("error", "Failed to insert into RDS: " + e.getMessage());
      return createErrorResponse(headers, responseJson, 500);
    }
  }

  private static Properties setMySqlConnectionProperties() throws Exception {
    Properties mysqlConnectionProperties = new Properties();
    mysqlConnectionProperties.setProperty("useSSL", "true");
    mysqlConnectionProperties.setProperty("user", DB_USER);
    mysqlConnectionProperties.setProperty("password", generateAuthToken());
    return mysqlConnectionProperties;
  }

  private static String generateAuthToken() throws Exception {
    RdsUtilities rdsUtilities = RdsUtilities.builder().build();
    return rdsUtilities.generateAuthenticationToken(
        GenerateAuthenticationTokenRequest.builder()
            .hostname(RDS_INSTANCE_HOSTNAME)
            .port(RDS_INSTANCE_PORT)
            .username(DB_USER)
            .region(Region.AP_SOUTHEAST_1)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build());
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
    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
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
