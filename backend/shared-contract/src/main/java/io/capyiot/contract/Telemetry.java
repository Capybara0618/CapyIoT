package io.capyiot.contract;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import java.util.List;

public final class Telemetry {
    private Telemetry() {}

    private static final TextMapGetter<String> PARENT = new TextMapGetter<>() {
        @Override public Iterable<String> keys(String value) { return List.of("traceparent"); }
        @Override public String get(String value, String key) { return "traceparent".equals(key) ? value : null; }
    };

    public static Span start(String name, SpanKind kind) {
        return GlobalOpenTelemetry.getTracer("io.capyiot").spanBuilder(name).setSpanKind(kind).startSpan();
    }

    public static String traceParent() {
        var context = Span.current().getSpanContext();
        if (!context.isValid()) return null;
        return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + context.getTraceFlags().asHex();
    }

    public static Scope parent(String value) {
        return W3CTraceContextPropagator.getInstance().extract(Context.root(), value, PARENT).makeCurrent();
    }

    public static String traceId(String fallback) {
        var context = Span.current().getSpanContext();
        return context.isValid() ? context.getTraceId() : fallback;
    }
}
