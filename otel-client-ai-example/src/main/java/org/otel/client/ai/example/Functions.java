package org.otel.client.ai.example;

import com.azure.ai.inference.models.ChatCompletionsToolCall;
import com.azure.ai.inference.models.ChatCompletionsToolDefinition;
import com.azure.ai.inference.models.ChatRequestToolMessage;
import com.azure.ai.inference.models.FunctionCall;
import com.azure.ai.inference.models.FunctionDefinition;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.core.util.tracing.Tracer;
import com.azure.json.JsonProviders;
import com.azure.json.JsonReader;
import com.azure.json.JsonSerializable;
import com.azure.json.JsonToken;
import com.azure.json.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class Functions {
    private final Tracer tracer;
    private final WeatherFunc weatherFunc;
    private final TemperatureFunc temperatureFunc;
    private final List<ChatCompletionsToolDefinition> toolDefinitions = new ArrayList<>(2);

    public Functions(Tracer tracer) {
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