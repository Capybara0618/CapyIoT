package io.capyiot.contract;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

public final class KafkaTelemetry {
    private KafkaTelemetry() {}

    private static final TextMapGetter<Headers> HEADERS = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Headers headers) {
            return Arrays.stream(headers.toArray()).map(Header::key).toList();
        }
        @Override public String get(Headers headers, String key) {
            Header header = headers == null ? null : headers.lastHeader(key);
            return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        }
    };

    public static Span process(ConsumerRecord<?, ?> record, String eventId) {
        Context parent = GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.root(), record.headers(), HEADERS);
        var span = GlobalOpenTelemetry.getTracer("io.capyiot")
                .spanBuilder(record.topic() + " process").setSpanKind(SpanKind.CONSUMER)
                .setParent(parent).setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.destination.name", record.topic())
                .setAttribute("messaging.kafka.offset", record.offset()).startSpan();
        if (eventId != null) span.setAttribute("capyiot.event_id", eventId);
        return span;
    }
}
