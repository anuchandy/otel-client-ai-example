package org.otel.client.ai.example;

import com.azure.ai.inference.ChatCompletionsClient;
import com.azure.ai.inference.ChatCompletionsClientBuilder;
import com.azure.ai.inference.models.ChatCompletionsOptions;
import com.azure.ai.inference.models.ChatCompletionsToolCall;
import com.azure.ai.inference.models.ChatRequestAssistantMessage;
import com.azure.ai.inference.models.ChatRequestMessage;
import com.azure.ai.inference.models.ChatRequestSystemMessage;
import com.azure.ai.inference.models.ChatRequestToolMessage;
import com.azure.ai.inference.models.ChatRequestUserMessage;
import com.azure.ai.inference.models.FunctionCall;
import com.azure.ai.inference.models.StreamingChatChoiceUpdate;
import com.azure.ai.inference.models.StreamingChatCompletionsUpdate;
import com.azure.ai.inference.models.StreamingChatResponseToolCallUpdate;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.util.Context;
import com.azure.core.util.CoreUtils;
import com.azure.core.util.IterableStream;
import com.azure.core.util.tracing.Tracer;

import java.util.ArrayList;
import java.util.List;

public class TraceStreamingChatCompletionSample {
    private static final String APP_NAMESPACE = "contoso-flight-info-app";
    static {
        Telemetry.initOTEL();
    }

    public static void main(final String[] args) {
        final Tracer appTracer = Telemetry.createTracer();
        final Context span = Telemetry.startSpan(appTracer, APP_NAMESPACE);
        try(AutoCloseable __ = appTracer.makeSpanCurrent(span)) {
            final ChatCompletionsClient client = createChatCompletionClient();

            final List<ChatRequestMessage> messages = new ArrayList<>();
            messages.add(new ChatRequestSystemMessage("You an assistant that helps users find flight information."));
            messages.add(new ChatRequestUserMessage("What is the next flights from Seattle to Miami?"));
            final GetFlightInfoFunction function = new GetFlightInfoFunction(appTracer);

            final IterableStream<StreamingChatCompletionsUpdate> toolCallChunks = client.completeStream(new ChatCompletionsOptions(messages).setTools(function.toolDefinitions()));
            final ChunksMerged toolCallChunksMerged = ChunksMerged.create(toolCallChunks);
            final ChatCompletionsToolCall toolCall = toolCallChunksMerged.asTooCall();
            messages.add(toAssistantMessage(toolCall));

            final ChatRequestToolMessage toolMessage = function.invoke(toolCall, span);
            messages.add(toolMessage);

            final IterableStream<StreamingChatCompletionsUpdate> modelResponseChunks = client.completeStream(new ChatCompletionsOptions(messages).setTools(function.toolDefinitions()));
            final ChunksMerged modelResponseChunksMerged = ChunksMerged.create(modelResponseChunks);
            System.out.println("Model response: " + modelResponseChunksMerged.content);

            Telemetry.endSpan(appTracer, null, span);
        } catch (Exception e) {
            Telemetry.endSpan(appTracer, e, span);
        }
    }

    private static ChatCompletionsClient createChatCompletionClient() {
        return new ChatCompletionsClientBuilder()
                .endpoint(System.getenv("AZURE_AI_CHAT_ENDPOINT"))
                .credential(new AzureKeyCredential(System.getenv("AZURE_AI_CHAT_KEY")))
                .buildClient();
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
                        // function response may be streamed across multiple chunks.
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
}
