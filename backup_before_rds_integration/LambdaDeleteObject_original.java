package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class LambdaDeleteObject
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent request, Context context) {

    LambdaLogger logger = context.getLogger();
    logger.log("Received request - Method: " + request.getHttpMethod() + ", Body: " + request.getBody());

    // --- Handle OPTIONS preflight request ---
    String method = request.getHttpMethod();
    if ("OPTIONS".equalsIgnoreCase(method)) {
      Map<String, String> headers = new HashMap<>();
      headers.put("Access-Control-Allow-Origin", "*");
      headers.put("Access-Control-Allow-Methods", "DELETE, OPTIONS");
      headers.put("Access-Control-Allow-Headers", "Content-Type");
      headers.put("Access-Control-Max-Age", "86400");
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(204)
          .withHeaders(headers);
    }

    // --- 1. Get the key from the request body or query parameters ---
    String key = null;
    String requestBody = request.getBody();

    if (requestBody != null && !requestBody.isEmpty()) {
      JSONObject bodyJSON = new JSONObject(requestBody);
      key = bodyJSON.optString("key", null);
    }

    if (key == null) {
      java.util.Map<String, String> params = request.getQueryStringParameters();
      if (params != null) {
        key = params.get("key");
      }
    }

    if (key == null || key.isEmpty()) {
      logger.log("Missing key parameter in request");
      JSONObject errorJson = new JSONObject();
      errorJson.put("error", "Missing key parameter");
      Map<String, String> headers = new HashMap<>();
      headers.put("Content-Type", "application/json");
      headers.put("Access-Control-Allow-Origin", "*");
      headers.put("Access-Control-Allow-Methods", "DELETE, OPTIONS");
      headers.put("Access-Control-Allow-Headers", "Content-Type");
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(400)
          .withBody(errorJson.toString())
          .withHeaders(headers);
    }

    // --- 2. Set the bucket name and region (same as your other Lambdas) ---
    String bucketName = "public-mg2";
    String resizedBucketName = "resizebucket-vy";
    Region region = Region.AP_SOUTHEAST_1;

    S3Client s3Client = S3Client.builder().region(region).build();

    // --- 3. Create the DeleteObjectRequest for original object ---
    DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder().bucket(bucketName).key(key).build();
    
    // --- 3b. Create the DeleteObjectRequest for resized object ---
    String resizedKey = "resized-" + key;
    DeleteObjectRequest deleteResizedRequest = DeleteObjectRequest.builder()
        .bucket(resizedBucketName)
        .key(resizedKey)
        .build();

    JSONObject responseJson = new JSONObject();
    APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

    // --- 4. Call the deleteObject method and handle success/failure ---
    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("Access-Control-Allow-Origin", "*");
    headers.put("Access-Control-Allow-Methods", "DELETE, OPTIONS");
    headers.put("Access-Control-Allow-Headers", "Content-Type");

    try {
      // Delete original object
      s3Client.deleteObject(deleteRequest);
      logger.log("Successfully deleted object: " + key + " from bucket: " + bucketName);
      
      // Delete resized object (if it exists)
      try {
        s3Client.deleteObject(deleteResizedRequest);
        logger.log("Successfully deleted resized object: " + resizedKey + " from bucket: " + resizedBucketName);
        responseJson.put("message", "Object and resized version deleted successfully: " + key);
      } catch (S3Exception e) {
        // If resized object doesn't exist, log but don't fail the request
        logger.log("Resized object not found or already deleted: " + resizedKey + " - " + e.getMessage());
        responseJson.put("message", "Object deleted successfully: " + key + " (resized version not found)");
      }

      response.setStatusCode(200);
      response.setBody(responseJson.toString());

    } catch (S3Exception e) {
      logger.log("Error deleting object: " + e.getMessage());

      responseJson.put("error", e.getMessage());
      response.setStatusCode(500); // Internal Server Error
      response.setBody(responseJson.toString());
    } catch (Exception e) {
      // Bắt mọi exception khác
      logger.log("Unexpected error: " + e.getMessage());

      responseJson.put("error", "Unexpected error: " + e.getMessage());
      response.setStatusCode(500);
      response.setBody(responseJson.toString());
    }

    // --- 5. Return the response ---
    response.setHeaders(headers);
    return response;
  }
}
