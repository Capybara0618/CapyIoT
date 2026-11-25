package io.capyiot.contract;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VideoDeviceMessageTest {
    private final ObjectMapper json=new ObjectMapper();
    private final Instant now=Instant.parse("2026-11-24T08:00:00Z");
    private static final String TOPIC="capyiot/v1/devices/ipc-1/sessions/boot-1/events";
    private ObjectNode heartbeat(){var e=json.createObjectNode().put("eventId",UUID.randomUUID().toString()).put("schemaVersion",1).put("deviceId","ipc-1").put("sessionId","boot-1").put("eventType","HEARTBEAT").put("streamKey","HEARTBEAT").put("sequence",1).put("occurredAt",now.toString());e.putObject("payload").put("bootId","boot-1");return e;}
    @Test void rejectsCredentialsUntrustedContextAndCrossDeviceTopics(){
        for(String field:new String[]{"sessionToken","source","parkId","deviceType","receivedAt"}){var e=heartbeat().put(field,"untrusted-value");assertThatThrownBy(()->VideoDeviceMessage.validate(e,TOPIC,"IPC",now)).isInstanceOf(IllegalArgumentException.class);}
        assertThatThrownBy(()->VideoDeviceMessage.validate(heartbeat(),TOPIC.replace("ipc-1","ipc-2"),"IPC",now)).isInstanceOf(IllegalArgumentException.class);
        var nested=heartbeat();((ObjectNode)nested.path("payload")).put("sessionToken","not-permitted");
        assertThatThrownBy(()->VideoDeviceMessage.validate(nested,TOPIC,"IPC",now)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void validatesIndependentCanonicalStreamsSequenceBoundsAndClockWindow(){
        var e=heartbeat();assertThat(VideoDeviceMessage.validate(e,TOPIC,"IPC",now).sequence()).isEqualTo(1);
        for(long sequence:new long[]{0,-1,9_007_199_254_740_992L}){var bad=heartbeat().put("sequence",sequence);assertThatThrownBy(()->VideoDeviceMessage.validate(bad,TOPIC,"IPC",now)).isInstanceOf(IllegalArgumentException.class);}
        var stale=heartbeat().put("occurredAt",now.minusSeconds(86_401).toString());assertThatThrownBy(()->VideoDeviceMessage.validate(stale,TOPIC,"IPC",now)).isInstanceOf(IllegalArgumentException.class);
        var future=heartbeat().put("occurredAt",now.plusSeconds(301).toString());assertThatThrownBy(()->VideoDeviceMessage.validate(future,TOPIC,"IPC",now)).isInstanceOf(IllegalArgumentException.class);
        var malformed=heartbeat().put("occurredAt","not-a-timeZ");assertThatThrownBy(()->VideoDeviceMessage.validate(malformed,TOPIC,"IPC",now)).isInstanceOf(IllegalArgumentException.class);
        var oversizedVersion=heartbeat().put("schemaVersion",4_294_967_297L);assertThatThrownBy(()->VideoDeviceMessage.validate(oversizedVersion,TOPIC,"IPC",now)).isInstanceOf(IllegalArgumentException.class);
        var observation=heartbeat().put("eventType","STATUS_OBSERVATION").put("streamKey","STATUS/VIDEO_CHANNEL/channel:1/RECORDING_STATUS");
        observation.putObject("payload").put("resourceType","VIDEO_CHANNEL").put("resourceKey","channel:1").put("observationType","RECORDING_STATUS").put("value","RECORDING").put("quality","GOOD");
        assertThat(VideoDeviceMessage.validate(observation,TOPIC,"NVR",now).streamKey()).contains("channel:1");
        assertThatThrownBy(()->VideoDeviceMessage.validate(observation,TOPIC,"IPC",now)).isInstanceOf(IllegalArgumentException.class);
    }
}
