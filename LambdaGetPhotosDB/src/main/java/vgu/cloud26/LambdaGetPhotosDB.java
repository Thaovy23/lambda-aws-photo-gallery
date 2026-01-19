package vgu.cloud26;
import com.amazonaws.services.lambda.runtime.Context;

import com.amazonaws.services.lambda.runtime.LambdaLogger;

import com.amazonaws.services.lambda.runtime.RequestHandler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import java.sql.Connection;

import java.sql.DriverManager;

import java.sql.PreparedStatement;

import java.sql.ResultSet;

import java.util.Base64;
import java.util.Properties;

import org.json.JSONArray;

import org.json.JSONObject;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import software.amazon.awssdk.regions.Region;

import software.amazon.awssdk.services.rds.RdsUtilities;

import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

 

public class LambdaGetPhotosDB implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

 

    private static final String RDS_INSTANCE_HOSTNAME

            = "mydatabase.cliyoi48e7ab.ap-southeast-1.rds.amazonaws.com";

    private static final int RDS_INSTANCE_PORT = 3306;

    private static final String DB_USER = "cloud26";

    private static final String JDBC_URL

            = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME

            + ":" + RDS_INSTANCE_PORT + "/Cloud26";

 

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("GetPhotosDB request received");

        if (isWarmUpRequest(request)) {
            return createWarmUpResponse();
        }

        JSONArray items = new JSONArray();

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            Connection mySQLClient = 
                    DriverManager.getConnection(JDBC_URL, 
                            setMySqlConnectionProperties());

            PreparedStatement st = mySQLClient.prepareStatement(
                    "SELECT * FROM Photos ORDER BY ID DESC"
            );
            ResultSet rs = st.executeQuery();
            
            while (rs.next()) {
                JSONObject item = new JSONObject();
                item.put("ID", rs.getInt("ID"));
                item.put("Description", rs.getString("Description"));
                item.put("S3Key", rs.getString("S3Key"));
                item.put("email", rs.getString("email"));
                items.put(item);
            }
            
            rs.close();
            st.close();
            mySQLClient.close();
            
            logger.log("Successfully fetched " + items.length() + " photos");

        } catch (ClassNotFoundException ex) {
            logger.log("ClassNotFoundException: " + ex.toString());
        } catch (Exception ex) {
            logger.log("Exception: " + ex.toString());
        }

        String encodedResult = 
                Base64.getEncoder()
                        .encodeToString(items.toString().getBytes());

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(200);
        response.setBody(encodedResult);
        response.withIsBase64Encoded(true);
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/json");
        response.setHeaders(headers);
        return response;

 

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

 

        // Generate the authentication token

        String authToken

                = rdsUtilities.generateAuthenticationToken(

                        GenerateAuthenticationTokenRequest.builder()

                                .hostname(RDS_INSTANCE_HOSTNAME)

                                .port(RDS_INSTANCE_PORT)

                                .username(DB_USER)

                                .region(Region.AP_SOUTHEAST_1)

                                .credentialsProvider(DefaultCredentialsProvider.create())

                                .build());

        return authToken;

    }

    private static boolean isWarmUpRequest(APIGatewayProxyRequestEvent request) {
        if (request == null) {
            return false;
        }

        java.util.Map<String, String> headers = request.getHeaders();
        if (headers != null) {
            String warmUpHeader = headers.get("x-warm-up");
            if ("true".equalsIgnoreCase(warmUpHeader)) {
                return true;
            }
        }

        java.util.Map<String, String> params = request.getQueryStringParameters();
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
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/json");
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withBody(responseJson.toString())
            .withHeaders(headers);
    }

}