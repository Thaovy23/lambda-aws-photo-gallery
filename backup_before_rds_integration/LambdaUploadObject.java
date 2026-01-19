package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;


public class LambdaUploadObject implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    @Override
    public APIGatewayProxyResponseEvent
            handleRequest(APIGatewayProxyRequestEvent event, Context context) {
       
        // CORS headers
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "POST, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type");
        headers.put("Content-Type", "application/json");
        
        // Handle OPTIONS preflight
        if ("OPTIONS".equalsIgnoreCase(event.getHttpMethod())) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(204)
                    .withHeaders(headers);
        }
        
        String bucketName = "public-mg2";
        String requestBody = event.getBody();
        
        // Check if body is null
        if (requestBody == null || requestBody.isEmpty()) {
            APIGatewayProxyResponseEvent errorResponse = new APIGatewayProxyResponseEvent();
            errorResponse.setStatusCode(400);
            errorResponse.setBody("{\"error\":\"Request body is empty\"}");
            errorResponse.setHeaders(headers);
            return errorResponse;
        }
        
        try {
            JSONObject bodyJSON = new JSONObject(requestBody);
            String content = bodyJSON.getString("content");
            String objName = bodyJSON.getString("key");
            
            byte[] objBytes = Base64.getDecoder().decode(content.getBytes());
            
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objName)
                    .build();

            S3Client s3Client = S3Client.builder()
                    .region(Region.AP_SOUTHEAST_1)
                    .build();
            s3Client.putObject(putObjectRequest,
                    RequestBody.fromBytes(objBytes));

            // Return JSON response
            JSONObject responseJson = new JSONObject();
            responseJson.put("message", "Object uploaded successfully");
            responseJson.put("key", objName);

            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(200);
            response.setBody(responseJson.toString());
            response.setHeaders(headers);
            
            return response;
            
        } catch (Exception e) {
            JSONObject errorJson = new JSONObject();
            errorJson.put("error", e.getMessage());
            
            APIGatewayProxyResponseEvent errorResponse = new APIGatewayProxyResponseEvent();
            errorResponse.setStatusCode(500);
            errorResponse.setBody(errorJson.toString());
            errorResponse.setHeaders(headers);
            
            return errorResponse;
        }
    }

}

