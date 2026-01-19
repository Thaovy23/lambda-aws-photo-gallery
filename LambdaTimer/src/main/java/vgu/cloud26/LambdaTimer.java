package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;

public class LambdaTimer implements RequestHandler<ScheduledEvent, APIGatewayProxyResponseEvent> {

    private static final String[] FUNCTIONS_TO_WARM = new String[] {
        "LambdaGenerateToken",
        "LambdaVerifyToken",
        "LambdaGetPhotosDB",
        "LambdaUploadWithDescription",
        "LambdaInsertPhoto",
        "Lamda_v2",
        "LambdaDeletePhoto",
        "LambdaDeleteObject",
        "LambdaDeletePhotoDB",
        "LambdaUploadObject",
        "LamdaResizer"
    };

    private final LambdaClient lambdaClient;

    public LambdaTimer() {
        this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_1).build();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(ScheduledEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("LambdaTimer: Warm-up trigger received");

        Map<String, String> results = new HashMap<>();

        for (String functionName : FUNCTIONS_TO_WARM) {
            try {
                JSONObject warmUpBody = new JSONObject();
                warmUpBody.put("warmup", true);

                JSONObject headers = new JSONObject();
                headers.put("x-warm-up", "true");

                JSONObject apiGatewayEvent = new JSONObject();
                apiGatewayEvent.put("httpMethod", "POST");
                apiGatewayEvent.put("body", warmUpBody.toString());
                apiGatewayEvent.put("headers", headers);
                apiGatewayEvent.put("isBase64Encoded", false);

                InvokeRequest invokeRequest = InvokeRequest.builder()
                    .functionName(functionName)
                    .invocationType("Event")
                    .payload(SdkBytes.fromUtf8String(apiGatewayEvent.toString()))
                    .build();

                lambdaClient.invoke(invokeRequest);
                results.put(functionName, "ok");
                logger.log("Warmed up: " + functionName);
            } catch (Exception e) {
                results.put(functionName, "error: " + e.getMessage());
                logger.log("Warm-up failed for " + functionName + ": " + e.getMessage());
            }
        }

        JSONObject responseJson = new JSONObject();
        responseJson.put("message", "Warm-up completed");
        responseJson.put("results", results);

        Map<String, String> responseHeaders = new HashMap<>();
        responseHeaders.put("Content-Type", "application/json");

        return new APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withBody(responseJson.toString())
            .withHeaders(responseHeaders);
    }
}