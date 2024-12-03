package org.otel.client.ai.example;

import com.azure.ai.inference.models.ChatCompletionsToolCall;
import com.azure.ai.inference.models.ChatCompletionsToolDefinition;
import com.azure.ai.inference.models.ChatRequestToolMessage;
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

/**
 * represents function tool ('get_flight_info') definition and react to model evaluation of function tool.
 */
public class GetFlightInfoFunction {
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
