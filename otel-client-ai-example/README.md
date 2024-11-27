## Running Aspire OTLP Collector and UI dashboard

Aspire is one of the OpenTelemetry collector and visualizer.

```
docker run --rm -p 18888:18888 -p 4317:18889 -p 4318:18890 --name aspire-dashboard mcr.microsoft.com/dotnet/nightly/aspire-dashboard:latest
```

The Aspire listens at http://localhost:4318 and OpenTelemetry Java agent (opentelemetry-javaagent.jar) sends the data to this endpoint.

## AIInferenceExample

Set the environment variables `AZURE_AI_CHAT_ENDPOINT` and `AZURE_AI_CHAT_KEY`.

