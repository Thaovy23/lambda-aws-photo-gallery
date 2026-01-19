package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.Properties;
import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

public class LambdaDeletePhotoDB
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
    logger.log("DeletePhotoDB request: " + request.getBody());

    Map<String, String> headers = new java.util.HashMap<>();
    headers.put("Content-Type", "application/json");

    JSONObject responseJson = new JSONObject();

    try {
      String requestBody = request.getBody();
      if (requestBody == null || requestBody.isEmpty()) {
        responseJson.put("error", "Request body is empty");
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(400)
            .withBody(responseJson.toString())
            .withHeaders(headers);
      }

      JSONObject bodyJSON = new JSONObject(requestBody);
      String s3Key = bodyJSON.optString("s3_key", null);
      Integer id =
          bodyJSON.has("id") && !bodyJSON.isNull("id") ? bodyJSON.optInt("id") : null;

      if ((s3Key == null || s3Key.isEmpty()) && id == null) {
        responseJson.put("error", "Either s3_key or id is required");
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(400)
            .withBody(responseJson.toString())
            .withHeaders(headers);
      }

      Class.forName("com.mysql.cj.jdbc.Driver");
      try (Connection mySQLClient =
          DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties())) {
        String sql;
        if (id != null) {
          sql = "DELETE FROM Photos WHERE ID = ?";
        } else {
          sql = "DELETE FROM Photos WHERE S3Key = ?";
        }

        try (PreparedStatement st = mySQLClient.prepareStatement(sql)) {
          if (id != null) {
            st.setInt(1, id);
          } else {
            st.setString(1, s3Key);
          }

          int rowsAffected = st.executeUpdate();

          responseJson.put("message", "Delete executed");
          responseJson.put("rows_affected", rowsAffected);
          if (s3Key != null) {
            responseJson.put("s3_key", s3Key);
          }
          if (id != null) {
            responseJson.put("id", id);
          }

          return new APIGatewayProxyResponseEvent()
              .withStatusCode(200)
              .withBody(responseJson.toString())
              .withHeaders(headers);
        }
      }
    } catch (ClassNotFoundException ex) {
      logger.log("ClassNotFoundException: " + ex);
      responseJson.put("error", "Database driver not found: " + ex.getMessage());
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(500)
          .withBody(responseJson.toString())
          .withHeaders(headers);
    } catch (Exception ex) {
      logger.log("Exception while deleting photo: " + ex);
      responseJson.put("error", "Failed to delete photo: " + ex.getMessage());
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(500)
          .withBody(responseJson.toString())
          .withHeaders(headers);
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
}

