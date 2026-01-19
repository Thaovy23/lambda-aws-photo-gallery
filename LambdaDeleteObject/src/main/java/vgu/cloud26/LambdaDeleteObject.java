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

    if (isWarmUpRequest(request)) {
      return createWarmUpResponse();
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

    try {
      // Step 1: Delete original object from public-mg2 bucket
      logger.log("Step 1: Deleting original image from bucket: " + bucketName + ", key: " + key);
      s3Client.deleteObject(deleteRequest);
      logger.log("✓ Successfully deleted original object: " + key + " from bucket: " + bucketName);
      responseJson.put("original_deleted", true);
      responseJson.put("original_bucket", bucketName);
      responseJson.put("original_key", key);
      
      // Step 2: Delete resized object from resizebucket-vy bucket
      logger.log("Step 2: Deleting resized image from bucket: " + resizedBucketName + ", key: " + resizedKey);
      try {
        s3Client.deleteObject(deleteResizedRequest);
        logger.log("✓ Successfully deleted resized object: " + resizedKey + " from bucket: " + resizedBucketName);
        responseJson.put("resized_deleted", true);
        responseJson.put("resized_bucket", resizedBucketName);
        responseJson.put("resized_key", resizedKey);
        responseJson.put("message", "Both original and resized images deleted successfully: " + key);
      } catch (S3Exception e) {
        // If resized object doesn't exist, log but don't fail the request
        logger.log("⚠ Resized object not found or already deleted: " + resizedKey + " - " + e.getMessage());
        responseJson.put("resized_deleted", false);
        responseJson.put("resized_error", "Resized object not found or already deleted");
        responseJson.put("message", "Original image deleted successfully: " + key + " (resized version not found)");
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
}
