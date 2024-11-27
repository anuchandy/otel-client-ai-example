package org.otel.client.ai.example;

import com.azure.ai.inference.ChatCompletionsClient;
import com.azure.ai.inference.ChatCompletionsClientBuilder;
import com.azure.ai.inference.models.ChatChoice;
import com.azure.ai.inference.models.ChatCompletions;
import com.azure.ai.inference.models.ChatCompletionsOptions;
import com.azure.ai.inference.models.ChatCompletionsToolCall;
import com.azure.ai.inference.models.ChatRequestAssistantMessage;
import com.azure.ai.inference.models.ChatRequestMessage;
import com.azure.ai.inference.models.ChatRequestSystemMessage;
import com.azure.ai.inference.models.ChatRequestToolMessage;
import com.azure.ai.inference.models.ChatRequestUserMessage;
import com.azure.ai.inference.models.CompletionsFinishReason;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.tracing.opentelemetry.OpenTelemetryTracingOptions;
import com.azure.core.util.ClientOptions;
import com.azure.core.util.Context;
import com.azure.core.util.TracingOptions;
import com.azure.core.util.tracing.Tracer;

import java.util.ArrayList;
import java.util.List;

public class AIInferenceExample {

    public static void main(final String[] args) {
        //
        final TracingOptions tracingOptions = new OpenTelemetryTracingOptions().setOpenTelemetry(Telemetry.createOTEL());
        final ChatCompletionsClient client = createChatCompletionClient(tracingOptions);
        //
        final Tracer appTracer = Telemetry.createTracer(tracingOptions);
        final Context span = Telemetry.startSpan(appTracer);
        try {
            final List<ChatRequestMessage> messages = new ArrayList<>();
            messages.add(new ChatRequestSystemMessage("You are a helpful assistant."));
            messages.add(new ChatRequestUserMessage("What is the weather and temperature in Seattle?"));
            // represents function tool ('get_weather', 'get_temperature') definitions and react to model evaluation of function tools.
            final Functions functions = new Functions(appTracer);

            // POST <namespace>.openai.azure.com/openai/deployments/gpt-4/chat/completion
            ChatCompletions response = client.complete(new ChatCompletionsOptions(messages).setTools(functions.toolDefinitions()), span);
            ChatChoice choice = response.getChoice();

            while (isToolCalls(choice)) {
                final List<ChatCompletionsToolCall> toolCalls = assertNonEmpty(choice.getMessage().getToolCalls());
                messages.add(toAssistantMessage(toolCalls));
                for (final ChatCompletionsToolCall toolCall : toolCalls) {
                    final ChatRequestToolMessage toolMessage = functions.invoke(toolCall, span);
                    messages.add(toolMessage);
                }
                // POST <namespace>.openai.azure.com/openai/deployments/gpt-4/chat/completion
                response = client.complete(new ChatCompletionsOptions(messages).setTools(functions.toolDefinitions()), span);
                choice = response.getChoice();
            }

            System.out.println("Model response: " + modelResponseContent(response));
            Telemetry.endSpan(appTracer, null, span);
        } catch (Exception e) {
            Telemetry.endSpan(appTracer, e, span);
        }
    }

    private static ChatCompletionsClient createChatCompletionClient(TracingOptions tracingOptions) {
        return new ChatCompletionsClientBuilder()
                .endpoint(System.getenv("AZURE_AI_CHAT_ENDPOINT"))
                .credential(new AzureKeyCredential(System.getenv("AZURE_AI_CHAT_KEY")))
                .clientOptions(new ClientOptions().setTracingOptions(tracingOptions))
                .buildClient();
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
}