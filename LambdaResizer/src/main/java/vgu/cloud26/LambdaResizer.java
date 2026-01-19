package vgu.cloud26;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.json.JSONObject;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class LambdaResizer
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final float MAX_DIMENSION = 100;
    private static final String REGEX = ".*\\.([^\\.]*)";
    private static final String JPG_TYPE = "jpg";
    private static final String JPEG_TYPE = "jpeg";
    private static final String JPG_MIME = "image/jpeg";
    private static final String PNG_TYPE = "png";
    private static final String PNG_MIME = "image/png";
    private static final String RESIZED_BUCKET = "resizebucket-vy";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
        APIGatewayProxyRequestEvent request, Context context) {
        LambdaLogger logger = context.getLogger();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        if (isWarmUpRequest(request)) {
            return createWarmUpResponse(headers);
        }

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
            String content = body.optString("content", null);

            if (key == null || key.isEmpty()) {
                responseJson.put("success", false);
                responseJson.put("error", "key or filename is required");
                return createErrorResponse(headers, responseJson, 400);
            }

            if (content == null || content.isEmpty()) {
                responseJson.put("success", false);
                responseJson.put("error", "content is required");
                return createErrorResponse(headers, responseJson, 400);
            }

            byte[] imageBytes;
            try {
                imageBytes = Base64.getDecoder().decode(content);
            } catch (IllegalArgumentException e) {
                responseJson.put("success", false);
                responseJson.put("error", "Invalid base64 content: " + e.getMessage());
                return createErrorResponse(headers, responseJson, 400);
            }

            Matcher matcher = Pattern.compile(REGEX).matcher(key);
            if (!matcher.matches()) {
                responseJson.put("success", false);
                responseJson.put("error", "Unable to infer image type for key: " + key);
                return createErrorResponse(headers, responseJson, 400);
            }

            String imageType = matcher.group(1).toLowerCase();
            if (!JPG_TYPE.equals(imageType) && !JPEG_TYPE.equals(imageType) && !PNG_TYPE.equals(imageType)) {
                responseJson.put("success", false);
                responseJson.put(
                    "error", "Unsupported image type: " + imageType + ". Only JPG and PNG are supported.");
                return createErrorResponse(headers, responseJson, 400);
            }

            BufferedImage srcImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (srcImage == null) {
                responseJson.put("success", false);
                responseJson.put("error", "Failed to read image from bytes");
                return createErrorResponse(headers, responseJson, 400);
            }

            BufferedImage resizedImage = resizeImage(srcImage);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(resizedImage, imageType, outputStream);

            String resizedKey = "resized-" + key;
            S3Client s3Client = S3Client.builder().region(Region.AP_SOUTHEAST_1).build();

            Map<String, String> metadata = new HashMap<>();
            metadata.put("Content-Length", Integer.toString(outputStream.size()));
            if (JPG_TYPE.equals(imageType) || JPEG_TYPE.equals(imageType)) {
                metadata.put("Content-Type", JPG_MIME);
            } else if (PNG_TYPE.equals(imageType)) {
                metadata.put("Content-Type", PNG_MIME);
            }

            PutObjectRequest putObjectRequest =
                PutObjectRequest.builder()
                    .bucket(RESIZED_BUCKET)
                    .key(resizedKey)
                    .metadata(metadata)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(outputStream.toByteArray()));

            responseJson.put("success", true);
            responseJson.put("message", "Resized image uploaded successfully");
            responseJson.put("bucket", RESIZED_BUCKET);
            responseJson.put("key", resizedKey);
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody(responseJson.toString())
                .withHeaders(headers);
        } catch (Exception e) {
            logger.log("LambdaResizer error: " + e.getMessage());
            responseJson.put("success", false);
            responseJson.put("error", "Failed to resize and upload: " + e.getMessage());
            return createErrorResponse(headers, responseJson, 500);
        }
    }

    private BufferedImage resizeImage(BufferedImage srcImage) {
        int srcHeight = srcImage.getHeight();
        int srcWidth = srcImage.getWidth();
        float scalingFactor = Math.min(MAX_DIMENSION / srcWidth, MAX_DIMENSION / srcHeight);
        int width = (int) (scalingFactor * srcWidth);
        int height = (int) (scalingFactor * srcHeight);

        BufferedImage resizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resizedImage.createGraphics();
        graphics.setPaint(Color.white);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(srcImage, 0, 0, width, height, null);
        graphics.dispose();
        return resizedImage;
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

