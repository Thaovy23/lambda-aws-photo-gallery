package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;


public class LambdaUploadObject implements
        RequestHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object>
            handleRequest(Map<String, Object> input, Context context) {
        
        LambdaLogger logger = context.getLogger();
        logger.log("LambdaUploadObject: Received input: " + input);
       
        Map<String, Object> response = new HashMap<>();

        if (isWarmUpRequest(input)) {
            response.put("statusCode", 200);
            response.put("message", "Warmed up");
            return response;
        }
        
        String bucketName = "public-mg2";
        
        try {
            // Extract key and content from input
            // Input can be either direct map or APIGatewayProxyRequestEvent format
            String key = null;
            String content = null;
            
            // Check if this is from APIGatewayProxyRequestEvent (has "body" field)
            if (input.containsKey("body")) {
                String bodyStr = (String) input.get("body");
                logger.log("LambdaUploadObject: Parsing body from APIGatewayProxyRequestEvent");
                JSONObject bodyJSON = new JSONObject(bodyStr);
                key = bodyJSON.getString("key");
                content = bodyJSON.getString("content");
            } else {
                // Direct invoke from Lambda (body is the payload itself)
                logger.log("LambdaUploadObject: Parsing direct Lambda invoke");
                key = (String) input.get("key");
                content = (String) input.get("content");
            }
            
            if (key == null || key.isEmpty()) {
                logger.log("LambdaUploadObject: key is missing");
                response.put("statusCode", 400);
                response.put("error", "key is required");
                return response;
            }
            
            if (content == null || content.isEmpty()) {
                logger.log("LambdaUploadObject: content is missing");
                response.put("statusCode", 400);
                response.put("error", "content is required");
                return response;
            }
            
            String objName = key;
            
            logger.log("LambdaUploadObject: Uploading key: " + objName);
            logger.log("LambdaUploadObject: Content length (base64): " + content.length());
            
            // FIX: Decode base64 string directly (Java 8+ supports String parameter)
            byte[] objBytes = Base64.getDecoder().decode(content);
            logger.log("LambdaUploadObject: Decoded bytes length: " + objBytes.length);
            
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objName)
                    .build();

            S3Client s3Client = S3Client.builder()
                    .region(Region.AP_SOUTHEAST_1)
                    .build();
            s3Client.putObject(putObjectRequest,
                    RequestBody.fromBytes(objBytes));
            
            logger.log("LambdaUploadObject: Successfully uploaded to S3: " + bucketName + "/" + objName);

            // Return success response
            response.put("statusCode", 200);
            response.put("message", "Object uploaded successfully");
            response.put("key", objName);
            
            return response;
            
        } catch (Exception e) {
            logger.log("LambdaUploadObject: Exception occurred: " + e.getMessage());
            logger.log("LambdaUploadObject: Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
            
            response.put("statusCode", 500);
            response.put("error", e.getMessage());
            
            return response;
        }
    }

    private static boolean isWarmUpRequest(Map<String, Object> input) {
        if (input == null) {
            return false;
        }

        Object warmup = input.get("warmup");
        if (warmup instanceof Boolean && (Boolean) warmup) {
            return true;
        }
        if (warmup instanceof String && "true".equalsIgnoreCase((String) warmup)) {
            return true;
        }

        Object headersObj = input.get("headers");
        if (headersObj instanceof Map) {
            Object headerValue = ((Map<?, ?>) headersObj).get("x-warm-up");
            if (headerValue instanceof String && "true".equalsIgnoreCase((String) headerValue)) {
                return true;
            }
        }

        if (input.containsKey("body")) {
            Object bodyObj = input.get("body");
            if (bodyObj instanceof String) {
                try {
                    JSONObject json = new JSONObject((String) bodyObj);
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
        }

        return false;
    }

}