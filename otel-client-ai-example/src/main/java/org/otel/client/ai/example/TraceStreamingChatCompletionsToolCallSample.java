package org.otel.client.ai.example;

import com.azure.ai.inference.ChatCompletionsClient;
import com.azure.ai.inference.ChatCompletionsClientBuilder;
import com.azure.ai.inference.models.ChatCompletionsOptions;
import com.azure.ai.inference.models.ChatCompletionsToolCall;
import com.azure.ai.inference.models.ChatCompletionsToolDefinition;
import com.azure.ai.inference.models.ChatRequestAssistantMessage;
import com.azure.ai.inference.models.ChatRequestMessage;
import com.azure.ai.inference.models.ChatRequestSystemMessage;
import com.azure.ai.inference.models.ChatRequestToolMessage;
import com.azure.ai.inference.models.ChatRequestUserMessage;
import com.azure.ai.inference.models.FunctionCall;
import com.azure.ai.inference.models.FunctionDefinition;
import com.azure.ai.inference.models.StreamingChatChoiceUpdate;
import com.azure.ai.inference.models.StreamingChatCompletionsUpdate;
import com.azure.ai.inference.models.StreamingChatResponseToolCallUpdate;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.core.util.CoreUtils;
import com.azure.core.util.IterableStream;
import com.azure.core.util.tracing.StartSpanOptions;
import com.azure.core.util.tracing.Tracer;
import com.azure.core.util.tracing.TracerProvider;
import com.azure.json.JsonProviders;
import com.azure.json.JsonReader;
import com.azure.json.JsonSerializable;
import com.azure.json.JsonToken;
import com.azure.json.JsonWriter;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.azure.core.util.tracing.SpanKind.CLIENT;

public class TraceStreamingChatCompletionsToolCallSample {
    private static final String APP_NAMESPACE = "contoso-flight-info-app";
    static {
        configureOTEL();
    }

    /**
     * @param args Unused. Arguments to the program.
     */
    @SuppressWarnings("try")
    public static void main(final String[] args) {
        final ChatCompletionsClient client = createChatCompletionClient();
        final Tracer tracer = createTracer();
        final Context span = tracer.start(APP_NAMESPACE, new StartSpanOptions(CLIENT), Context.NONE);
        try(AutoCloseable scope = tracer.makeSpanCurrent(span)) {
            final List<ChatRequestMessage> messages = new ArrayList<>();
            messages.add(new ChatRequestSystemMessage("You an assistant that helps users find flight information."));
            messages.add(new ChatRequestUserMessage("What is the next flights from Seattle to Miami?"));
            final GetFlightInfoFunction function = new GetFlightInfoFunction(tracer);

            final IterableStream<StreamingChatCompletionsUpdate> toolCallChunks = client.completeStream(new ChatCompletionsOptions(messages).setTools(function.toolDefinitions()));
            final ChunksMerged toolCallChunksMerged = ChunksMerged.create(toolCallChunks);
            final ChatCompletionsToolCall toolCall = toolCallChunksMerged.asTooCall();
            messages.add(toAssistantMessage(toolCall));

            final ChatRequestToolMessage toolMessage = function.invoke(toolCall, span);
            messages.add(toolMessage);

            final IterableStream<StreamingChatCompletionsUpdate> modelResponseChunks = client.completeStream(new ChatCompletionsOptions(messages).setTools(function.toolDefinitions()));
            final ChunksMerged modelResponseChunksMerged = ChunksMerged.create(modelResponseChunks);
            System.out.println("Model response: " + modelResponseChunksMerged.content);
            tracer.end(null, null, span);
        } catch (Exception e) {
            tracer.end(null, e, span);
        }
    }

    private static void configureOTEL() {
        // With the below configuration, the runtime sends OpenTelemetry data to the local OTLP/gRPC endpoint.
        //
        // For debugging purposes, Aspire Dashboard can be run locally that listens for telemetry data and offer a UI
        // for viewing the collected data. To run Aspire Dashboard, run the following docker command:
        //
        // docker run --rm -p 18888:18888 -p 4317:18889 -p 4318:18890 --name aspire-dashboard mcr.microsoft.com/dotnet/nightly/aspire-dashboard:latest
        //
        // The output of the docker command includes a link to the dashboard. For more information on Aspire Dashboard,
        // see https://learn.microsoft.com/dotnet/aspire/fundamentals/dashboard/overview
        //
        // For production telemetry use cases, see Azure Monitor, https://learn.microsoft.com/java/api/overview/azure/monitor-opentelemetry-exporter-readme
        //
        final AutoConfiguredOpenTelemetrySdkBuilder sdkBuilder = AutoConfiguredOpenTelemetrySdk.builder();
        sdkBuilder
                .addPropertiesSupplier(() -> {
                    final Map<String, String> properties = new HashMap<>();
                    properties.put("otel.exporter.otlp.endpoint", "http://localhost:4317");
                    return properties;
                })
                .setResultAsGlobal()
                .build()
                .getOpenTelemetrySdk();
    }

    private static ChatCompletionsClient createChatCompletionClient() {
        return new ChatCompletionsClientBuilder()
                .endpoint(System.getenv("MODEL_ENDPOINT"))
                .credential(new AzureKeyCredential(System.getenv("AZURE_API_KEY")))
                .buildClient();
    }

    private static Tracer createTracer() {
        return TracerProvider.getDefaultProvider().createTracer("demo-app", "1.0", "Contoso.App", null);
    }

    private static ChatRequestAssistantMessage toAssistantMessage(ChatCompletionsToolCall toolCall) {
        final List<ChatCompletionsToolCall> toolCalls = new ArrayList<>(1);
        toolCalls.add(toolCall);
        return new ChatRequestAssistantMessage("").setToolCalls(toolCalls);
    }

    private static final class ChunksMerged {
        private final String toolCallId;
        private final String functionName;
        private final String functionArguments;
        private final String content;

        static ChunksMerged create(IterableStream<StreamingChatCompletionsUpdate> chunks) {
            String toolCallId = null;
            String functionName = null;
            StringBuilder functionArguments = new StringBuilder();
            StringBuilder content = new StringBuilder();

            for (StreamingChatCompletionsUpdate chunk : chunks) {
                if (chunk.getChoices() == null || chunk.getChoices().isEmpty()) {
                    continue;
                }
                final StreamingChatChoiceUpdate choice = chunk.getChoices().get(0);
                if (choice != null && choice.getDelta() != null) {
                    final String contentChunk = choice.getDelta().getContent();
                    if (contentChunk != null) {
                        // function response content may be streamed across multiple chunks.
                        content.append(contentChunk);
                    }
                    if (choice.getDelta().getToolCalls() != null) {
                        final List<StreamingChatResponseToolCallUpdate> toolCalls = choice.getDelta().getToolCalls();
                        if (!toolCalls.isEmpty()) {
                            final StreamingChatResponseToolCallUpdate toolCall = toolCalls.get(0);
                            if (!CoreUtils.isNullOrEmpty(toolCall.getId())) {
                                toolCallId = toolCall.getId();
                            }
                            final FunctionCall functionCall = toolCall.getFunction();
                            if (functionCall != null) {
                                if (!CoreUtils.isNullOrEmpty(functionCall.getName())) {
                                    functionName = functionCall.getName();
                                }
                                if (functionCall.getArguments() != null) {
                                    // function arguments may be streamed across multiple chunks.
                                    functionArguments.append(functionCall.getArguments());
                                }
                            }
                        }
                    }
                }
            }
            return new ChunksMerged(toolCallId, functionName, functionArguments.toString(), content.toString());
        }

        ChatCompletionsToolCall asTooCall() {
            return new ChatCompletionsToolCall(toolCallId, new FunctionCall(functionName, functionArguments));
        }

        private ChunksMerged(String toolCallId, String functionName, String functionArguments, String content) {
            this.toolCallId = toolCallId;
            this.functionName = functionName;
            this.functionArguments = functionArguments;
            this.content = content;
        }
    }

    /**
     * represents function tool ('get_flight_info') definition and react to model evaluation of function tool.
     */
    private static class GetFlightInfoFunction {
        private final Tracer tracer;
        private final FlightInfoFunc flightInfoFunc;
        private final List<ChatCompletionsToolDefinition> toolDefinitions = new ArrayList<>(1);

        public GetFlightInfoFunction(Tracer tracer) {
            this.tracer = tracer;
            this.flightInfoFunc = new FlightInfoFunc();
            this.toolDefinitions.add(new ChatCompletionsToolDefinition(flightInfoFunc.getDefinition()));
        }

        public List<ChatCompletionsToolDefinition> toolDefinitions() {
            return this.toolDefinitions;
        }

        public ChatRequestToolMessage invoke(ChatCompletionsToolCall toolCall, Context span) {
            final Optional<ChatRequestToolMessage> fResponse = flightInfoFunc.tryInvoke(toolCall, tracer, span);
            if (fResponse.isPresent()) {
                return fResponse.get();
            }
            throw new RuntimeException("Service requested tool-call has no matching function information.");
        }

        private static final class FlightInfoFunc {
            private FunctionDefinition getDefinition() {
                return new FunctionDefinition("get_flight_info")
                        .setDescription("Returns information about the next flight between two cities. This includes the name of the airline, flight number and the date and time of the next flight, in JSON format.")
                        .setParameters(BinaryData.fromBytes(parameters()));
            }

            private Optional<ChatRequestToolMessage> tryInvoke(ChatCompletionsToolCall toolCall, Tracer tracer, Context span) {
                final String toolCallId = toolCall.getId();
                final String funcName = toolCall.getFunction().getName();
                final String funcArguments = toolCall.getFunction().getArguments();

                if (funcName.equalsIgnoreCase("get_flight_info")) {
                    final Context localSpan = tracer.start("local_get_flight_info", span);
                    try (AutoCloseable ignored = tracer.makeSpanCurrent(localSpan)) {
                        final FunctionArguments functionArguments = BinaryData.fromString(funcArguments).toObject(FunctionArguments.class);
                        tracer.setAttribute("parameter.origin_city", functionArguments.getOriginCity(), localSpan);
                        tracer.setAttribute("parameter.destination_city", functionArguments.getDestinationCity(), localSpan);
                        // sleep();
                        final String functionResponse;
                        if ("Seattle".equalsIgnoreCase(functionArguments.getOriginCity()) && "Miami".equalsIgnoreCase(functionArguments.getDestinationCity())) {
                            functionResponse = "{\"airline\": \"Delta\", \"flight_number\": \"DL123\", \"flight_date\": \"May 7th, 2024\", \"flight_time\": \"10:00AM\"}";
                        } else {
                            functionResponse = "{\"error\": \"No flights found between the cities\"}";
                        }
                        tracer.end(null, null, localSpan);
                        return Optional.of(new ChatRequestToolMessage(functionResponse, toolCallId));
                    } catch (Exception ex) {
                        tracer.end("local_get_flight_info failed.", ex, localSpan);
                    }
                }
                return Optional.empty();
            }

            private static byte[] parameters() {
                try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                     JsonWriter jsonWriter = JsonProviders.createWriter(byteArrayOutputStream)) {
                    jsonWriter.writeStartObject();
                    jsonWriter.writeStringField("type", "object");
                    jsonWriter.writeStartObject("properties");
                    jsonWriter.writeStartObject("origin_city");
                    jsonWriter.writeStringField("type", "string");
                    jsonWriter.writeStringField("description", "The name of the city where the flight originates");
                    jsonWriter.writeEndObject();
                    jsonWriter.writeStartObject("destination_city");
                    jsonWriter.writeStringField("type", "string");
                    jsonWriter.writeStringField("description", "The flight destination city");
                    jsonWriter.writeEndObject();
                    jsonWriter.writeEndObject();
                    jsonWriter.writeStartArray("required");
                    jsonWriter.writeString("origin_city");
                    jsonWriter.writeString("destination_city");
                    jsonWriter.writeEndArray();
                    jsonWriter.writeEndObject();
                    jsonWriter.flush();
                    return byteArrayOutputStream.toByteArray();
                } catch (IOException ioe) {
                    throw new UncheckedIOException(ioe);
                }
            }

            private static void sleep() {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    // ignored
                }
            }
        }

        private static final class FunctionArguments implements JsonSerializable<FunctionArguments> {
            private final String originCity;
            private final String destinationCity;

            private FunctionArguments(String originCity, String destinationCity) {
                this.originCity = originCity;
                this.destinationCity = destinationCity;
            }

            public String getOriginCity() {
                return this.originCity;
            }

            public String getDestinationCity() {
                return this.destinationCity;
            }

            @Override
            public JsonWriter toJson(JsonWriter jsonWriter) throws IOException {
                jsonWriter.writeStartObject();
                jsonWriter.writeStringField("origin_city", this.originCity);
                jsonWriter.writeStringField("destination_city", this.originCity);
                return jsonWriter.writeEndObject();
            }

            public static FunctionArguments fromJson(JsonReader jsonReader) throws IOException {
                return jsonReader.readObject(reader -> {
                    String originCity = null;
                    String destinationCity = null;
                    while (reader.nextToken() != JsonToken.END_OBJECT) {
                        String fieldName = reader.getFieldName();
                        reader.nextToken();
                        if ("origin_city".equals(fieldName)) {
                            originCity = reader.getString();
                        } else if ("destination_city".equals(fieldName)) {
                            destinationCity = reader.getString();
                        } else {
                            reader.skipChildren();
                        }
                    }
                    return new FunctionArguments(originCity, destinationCity);
                });
            }
        }
    }
}
