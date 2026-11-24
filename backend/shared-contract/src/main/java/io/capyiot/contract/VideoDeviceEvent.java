package io.capyiot.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Server enriched event; no transport secrets and no client supplied authorization context. */
public record VideoDeviceEvent(String eventId,int schemaVersion,String deviceId,String sessionId,String eventType,String streamKey,
                               long sequence,Instant occurredAt,JsonNode payload,String parkId,String deviceType,String source,
                               long sessionEpoch,long credentialVersion,Instant receivedAt,String traceId,String producer) {}
