package org.otel.client.ai.example;

import com.azure.ai.inference.ChatCompletionsClient;
import com.azure.ai.inference.ChatCompletionsClientBuilder;
import com.azure.ai.inference.models.ChatChoice;
import com.azure.ai.inference.models.ChatCompletions;
import com.azure.ai.inference.models.ChatCompletionsOptions;
import com.azure.ai.inference.models.ChatCompletionsToolCall;
import com.azure.ai.inference.models.ChatCompletionsToolDefinition;
import com.azure.ai.inference.models.ChatRequestAssistantMessage;
import com.azure.ai.inference.models.ChatRequestMessage;
import com.azure.ai.inference.models.ChatRequestSystemMessage;
import com.azure.ai.inference.models.ChatRequestToolMessage;
import com.azure.ai.inference.models.ChatRequestUserMessage;
import com.azure.ai.inference.models.CompletionsFinishReason;
import com.azure.ai.inference.models.FunctionCall;
import com.azure.ai.inference.models.FunctionDefinition;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
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

public class TraceChatCompletionsToolCallSample {
    private static final String APP_NAMESPACE = "contoso-weather-temperature-app";
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
            messages.add(new ChatRequestSystemMessage("You are a helpful assistant."));
            messages.add(new ChatRequestUserMessage("What is the weather and temperature in Seattle?"));
            final GetWeatherTemperatureFunctions functions = new GetWeatherTemperatureFunctions(tracer);

            ChatCompletions response = client.complete(new ChatCompletionsOptions(messages).setTools(functions.toolDefinitions()));
            ChatChoice choice = response.getChoice();

            while (isToolCalls(choice)) {
                final List<ChatCompletionsToolCall> toolCalls = assertNonEmpty(choice.getMessage().getToolCalls());
                messages.add(toAssistantMessage(toolCalls));
                for (final ChatCompletionsToolCall toolCall : toolCalls) {
                    final ChatRequestToolMessage toolMessage = functions.invoke(toolCall, span);
                    messages.add(toolMessage);
                }
                response = client.complete(new ChatCompletionsOptions(messages).setTools(functions.toolDefinitions()));
                choice = response.getChoice();
            }

            System.out.println("Model response: " + modelResponseContent(response));
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
                    properties.put("otel.exporter.otlp.endpoint", "http://localhost:4317"); // The OTLP/gRPC endpoint.
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

    private static boolean isToolCalls(ChatChoice choice) {
        return choice.getFinishReason() == CompletionsFinishReason.TOOL_CALLS;
    }

    private static List<ChatCompletionsToolCall> assertNonEmpty(List<ChatCompletionsToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new RuntimeException("Service requested tool-calls, but without information about function(s) to invoke.");
        }
        return toolCalls;
    }

    private static ChatRequestAssistantMessage toAssistantMessage(List<ChatCompletionsToolCall> toolCalls) {
        return new ChatRequestAssistantMessage("").setToolCalls(toolCalls);
    }

    private static String modelResponseContent(ChatCompletions response) {
        return response.getChoices().get(0).getMessage().getContent();
    }

    /**
     * represents function tool ('get_weather', 'get_temperature') definitions and react to model evaluation of function tools.
     */
    private static final class GetWeatherTemperatureFunctions {
        private final Tracer tracer;
        private final WeatherFunc weatherFunc;
        private final TemperatureFunc temperatureFunc;
        private final List<ChatCompletionsToolDefinition> toolDefinitions = new ArrayList<>(2);

        public GetWeatherTemperatureFunctions(Tracer tracer) {
            this.tracer = tracer;
            this.weatherFunc = new WeatherFunc();
            this.temperatureFunc = new TemperatureFunc();
            this.toolDefinitions.add(new ChatCompletionsToolDefinition(weatherFunc.getDefinition()));
            this.toolDefinitions.add(new ChatCompletionsToolDefinition(temperatureFunc.getDefinition()));
        }

        public List<ChatCompletionsToolDefinition> toolDefinitions() {
            return this.toolDefinitions;
        }

        public ChatRequestToolMessage invoke(ChatCompletionsToolCall toolCall, Context span) {
            final Optional<ChatRequestToolMessage> wResponse = weatherFunc.tryInvoke(toolCall, tracer, span);
            if (wResponse.isPresent()) {
                return wResponse.get();
            }
            final Optional<ChatRequestToolMessage> rwResponse = temperatureFunc.tryInvoke(toolCall, tracer, span);
            if (rwResponse.isPresent()) {
                return rwResponse.get();
            }
            throw new RuntimeException("Service requested tool-call has no matching function information.");
        }

        private static final class WeatherFunc {
            private FunctionDefinition getDefinition() {
                return new FunctionDefinition("get_weather")
                        .setDescription("Returns description of the weather in the specified city")
                        .setParameters(BinaryData.fromBytes(parameters()));
            }

            private Optional<ChatRequestToolMessage> tryInvoke(ChatCompletionsToolCall toolCall, Tracer tracer, Context span) {
                final FunctionCall function = toolCall.getFunction();
                final String functionName = function.getName();
                if (functionName.equalsIgnoreCase("get_weather")) {
                    final Context localSpan = tracer.start("local_get_weather", span);
                    try (AutoCloseable ignored = tracer.makeSpanCurrent(localSpan)) {
                        final FunctionArguments functionArguments = BinaryData.fromString(function.getArguments()).toObject(FunctionArguments.class);
                        tracer.setAttribute("parameter.city", functionArguments.getCity(), localSpan);
                        // sleep();
                        final String functionResponse;
                        if ("Seattle".equalsIgnoreCase(functionArguments.getCity())) {
                            functionResponse = "Nice weather";
                        } else if ("New York City".equalsIgnoreCase(functionArguments.getCity())) {
                            functionResponse = "Good weather";
                        } else {
                            functionResponse = "Unavailable";
                        }
                        tracer.end(null, null, localSpan);
                        return Optional.of(new ChatRequestToolMessage(functionResponse, toolCall.getId()));
                    } catch (Exception ex) {
                        tracer.end("local_get_weather failed.", ex, localSpan);
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
                    jsonWriter.writeStartObject("city");
                    jsonWriter.writeStringField("type", "string");
                    jsonWriter.writeStringField("description", "The name of the city for which weather info is requested");
                    jsonWriter.writeEndObject();
                    jsonWriter.writeEndObject();
                    jsonWriter.writeStartArray("required");
                    jsonWriter.writeString("city");
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

        private static final class TemperatureFunc {
            private FunctionDefinition getDefinition() {
                return new FunctionDefinition("get_temperature")
                        .setDescription("Returns the current temperature for the specified city")
                        .setParameters(BinaryData.fromBytes(parameters()));
            }

            private Optional<ChatRequestToolMessage> tryInvoke(ChatCompletionsToolCall toolCall, Tracer tracer, Context span) {
                final FunctionCall function = toolCall.getFunction();
                final String functionName = function.getName();
                if (functionName.equalsIgnoreCase("get_temperature")) {
                    final Context localSpan = tracer.start("local_get_temperature", span);
                    try (AutoCloseable ignored = tracer.makeSpanCurrent(localSpan)) {
                        final FunctionArguments functionArguments = BinaryData.fromString(function.getArguments()).toObject(FunctionArguments.class);
                        tracer.setAttribute("parameter.city", functionArguments.getCity(), localSpan);
                        // sleep();
                        final String functionResponse;
                        if ("Seattle".equalsIgnoreCase(functionArguments.getCity())) {
                            functionResponse = "75";
                        } else if ("New York City".equalsIgnoreCase(functionArguments.getCity())) {
                            functionResponse = "80";
                        } else {
                            functionResponse = "Unavailable";
                        }
                        tracer.end(null, null, localSpan);
                        return Optional.of(new ChatRequestToolMessage(functionResponse, toolCall.getId()));
                    } catch (Exception ex) {
                        tracer.end("local_get_temperature failed.", ex, localSpan);
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
                    jsonWriter.writeStartObject("city");
                    jsonWriter.writeStringField("type", "string");
                    jsonWriter.writeStringField("description", "The name of the city for which temperature info is requested");
                    jsonWriter.writeEndObject();
                    jsonWriter.writeEndObject();
                    jsonWriter.writeStartArray("required");
                    jsonWriter.writeString("city");
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
            private final String city;

            private FunctionArguments(String city) {
                this.city = city;
            }

            public String getCity() {
                return this.city;
            }

            @Override
            public JsonWriter toJson(JsonWriter jsonWriter) throws IOException {
                jsonWriter.writeStartObject();
                jsonWriter.writeStringField("city", this.city);
                return jsonWriter.writeEndObject();
            }

            public static FunctionArguments fromJson(JsonReader jsonReader) throws IOException {
                return jsonReader.readObject(reader -> {
                    String city = null;
                    while (reader.nextToken() != JsonToken.END_OBJECT) {
                        String fieldName = reader.getFieldName();
                        reader.nextToken();
                        if ("city".equals(fieldName)) {
                            city = reader.getString();
                        } else {
                            reader.skipChildren();
                        }
                    }
                    return new FunctionArguments(city);
                });
            }
        }
    }
}
