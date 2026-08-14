package com.dcbate.tradingplatform.config;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Boot 4.1.0 doesn't yet ship two pieces this app needs for tracing to actually reach Jaeger:
 * {@code spring-boot-opentelemetry} only wires SDK setup and log export (no span exporter), and
 * {@code spring-boot-micrometer-tracing} only wires the observation handlers <em>around</em> an
 * existing {@code io.micrometer.tracing.Tracer} bean — it never creates one itself, so
 * {@code NoopTracerAutoConfiguration}'s fallback silently wins and every span is discarded before
 * export. This class provides both: an {@link SdkTracerProvider} exporting via OTLP/HTTP (which
 * {@code OpenTelemetrySdkAutoConfiguration} picks up via {@code ObjectProvider}), and the
 * {@code OtelTracer} bridge that makes Micrometer's Observation API (already instrumenting Spring
 * MVC/Kafka) actually produce real spans instead of no-ops.
 */
@Configuration
public class TracingConfig {

    @Bean
    public SdkTracerProvider sdkTracerProvider(
            Resource openTelemetryResource,
            @Value("${management.otlp.tracing.endpoint}") String otlpEndpoint,
            @Value("${management.tracing.sampling.probability:1.0}") double samplingProbability) {
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder().setEndpoint(otlpEndpoint).build();
        return SdkTracerProvider.builder()
                .setResource(openTelemetryResource)
                .setSampler(Sampler.traceIdRatioBased(samplingProbability))
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();
    }

    @Bean
    public Tracer micrometerTracer(OpenTelemetry openTelemetry, ApplicationEventPublisher eventPublisher) {
        io.opentelemetry.api.trace.Tracer otelApiTracer = openTelemetry.getTracer("com.dcbate.tradingplatform");
        return new OtelTracer(otelApiTracer, new OtelCurrentTraceContext(), eventPublisher::publishEvent);
    }
}
