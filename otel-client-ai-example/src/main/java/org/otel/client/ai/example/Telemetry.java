package org.otel.client.ai.example;

import com.azure.core.util.Context;
import com.azure.core.util.tracing.StartSpanOptions;
import com.azure.core.util.tracing.Tracer;
import com.azure.core.util.tracing.TracerProvider;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;

import java.util.HashMap;
import java.util.Map;

import static com.azure.core.util.tracing.SpanKind.CLIENT;

final class Telemetry {
    static void initOTEL() {
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

    static Tracer createTracer() {
        return TracerProvider.getDefaultProvider().createTracer("demo-app", "1.0", "Contoso.App", null);
    }

    static Context startSpan(Tracer tracer, String namespace) {
        return tracer.start(namespace, new StartSpanOptions(CLIENT), Context.NONE);
    }

    static void endSpan(Tracer tracer, Exception e, Context span) {
        if (e == null) {
            tracer.end(null, null, span);
        }
        tracer.end(null, e, span);
    }
}
