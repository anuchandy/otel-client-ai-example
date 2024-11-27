package org.otel.client.ai.example;

import com.azure.core.util.Context;
import com.azure.core.util.TracingOptions;
import com.azure.core.util.tracing.StartSpanOptions;
import com.azure.core.util.tracing.Tracer;
import com.azure.core.util.tracing.TracerProvider;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;

import java.util.HashMap;
import java.util.Map;

import static com.azure.core.util.tracing.SpanKind.CLIENT;

final class Telemetry {
    private static final String APP_NAMESPACE = "contoso-weather-temperature-app";

    static OpenTelemetry createOTEL() {
        final AutoConfiguredOpenTelemetrySdkBuilder sdkBuilder = AutoConfiguredOpenTelemetrySdk.builder();
        return sdkBuilder
                .addPropertiesSupplier(() -> {
                    final Map<String, String> properties = new HashMap<>();
                    properties.put("otel.exporter.otlp.endpoint", "http://localhost:4318"); // 4317
                    properties.put("otel.exporter.otlp.insecure", "true");
                    properties.put("otel.exporter.otlp.protocol", "http/protobuf");
                    return properties;
                })
                .setResultAsGlobal() //
                .build()
                .getOpenTelemetrySdk();
    }

    static Tracer createTracer(TracingOptions tracingOptions) {
        return TracerProvider.getDefaultProvider().createTracer("demo-app", "1.0", "Contoso.App", tracingOptions);
    }

    static Context startSpan(Tracer tracer) {
        return tracer.start(APP_NAMESPACE, new StartSpanOptions(CLIENT), Context.NONE);
    }

    static void endSpan(Tracer tracer, Exception e, Context span) {
        if (e == null) {
            tracer.end(null, null, span);
        }
        tracer.end(null, e, span);
    }
}
