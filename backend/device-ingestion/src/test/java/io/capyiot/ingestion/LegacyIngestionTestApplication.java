package io.capyiot.ingestion;

/** Isolates the historical HMAC ingress fixture from native TLS/service-identity ingestion. */
@org.springframework.boot.SpringBootConfiguration
@org.springframework.boot.autoconfigure.EnableAutoConfiguration
@org.springframework.context.annotation.Import(MqttIngestion.class)
class LegacyIngestionTestApplication {}
