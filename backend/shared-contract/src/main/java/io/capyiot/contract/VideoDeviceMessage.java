package io.capyiot.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

/** A09 V1 wire contract. Transport credentials deliberately have no field here. */
public record VideoDeviceMessage(String eventId,int schemaVersion,String deviceId,String sessionId,String eventType,
                                 String streamKey,long sequence,Instant occurredAt,JsonNode payload) {
    public static final String HEARTBEAT_TOPIC="capyiot.device.heartbeat.v1";
    public static final String STATUS_TOPIC="capyiot.device.status.v1";
    public static final String EVENT_TOPIC="capyiot.device.event.v1";
    private static final Set<String> ENVELOPE=Set.of("eventId","schemaVersion","deviceId","sessionId","eventType","streamKey","sequence","occurredAt","payload");
    private static final Map<String,Set<String>> VALUES=Map.of(
            "OPERATING_MODE",Set.of("NORMAL","LOW_POWER","UNKNOWN"),"VIDEO_SIGNAL",Set.of("PRESENT","LOST","UNKNOWN"),
            "RECORDING_STATUS",Set.of("RECORDING","NOT_RECORDING","UNKNOWN"),"LINK_STATUS",Set.of("UP","DOWN","UNKNOWN"),
            "POE_STATUS",Set.of("POWERED","UNPOWERED","FAULT","UNKNOWN"));
    private static final Set<String> FAULTS=Set.of("CAMERA_NO_VIDEO","CAMERA_STREAM_UNAVAILABLE","CAMERA_OVERHEAT","CAMERA_DEVICE_ERROR",
            "NVR_RECORDING_STOPPED","NVR_CHANNEL_LOST","NVR_STORAGE_FULL","NVR_DISK_FAULT","NVR_DEVICE_ERROR",
            "SWITCH_PORT_LINK_DOWN","SWITCH_POE_FAULT","SWITCH_DEVICE_ERROR","SWITCH_OVERHEAT");
    public static VideoDeviceMessage validate(JsonNode root,String topic,String deviceType,Instant receivedAt) {
        fields(root,ENVELOPE,ENVELOPE);
        text(root,"eventId",36); uuid(root.path("eventId").asText()); text(root,"deviceId",64);text(root,"sessionId",64);text(root,"streamKey",128);
        require(root.path("schemaVersion").isIntegralNumber()&&root.path("schemaVersion").asInt()==1);
        require(root.path("sequence").isIntegralNumber()&&root.path("sequence").canConvertToLong()); long seq=root.path("sequence").asLong();require(seq>=1&&seq<=9007199254740991L);
        String time=text(root,"occurredAt",64);require(time.endsWith("Z"));Instant occurred=Instant.parse(time);
        require(!occurred.isBefore(receivedAt.minusSeconds(86400))&&!occurred.isAfter(receivedAt.plusSeconds(300)));
        String device=text(root,"deviceId",64),session=text(root,"sessionId",64);
        require(device.matches("[A-Za-z0-9_-]{1,64}")&&session.matches("[A-Za-z0-9_-]{1,64}"));
        require(topic.equals("capyiot/v1/devices/"+device+"/sessions/"+session+"/events"));
        JsonNode payload=root.path("payload");String type=text(root,"eventType",32),stream=text(root,"streamKey",128);
        switch(type) {
            case "HEARTBEAT" -> {
                fields(payload,Set.of("bootId","uptimeSeconds","firmwareVersion"),Set.of("bootId"));text(payload,"bootId",64);
                if(payload.has("uptimeSeconds"))require(payload.path("uptimeSeconds").isIntegralNumber()&&payload.path("uptimeSeconds").canConvertToLong()&&payload.path("uptimeSeconds").asLong()>=0);
                optional(payload,"firmwareVersion",64);require(stream.equals("HEARTBEAT"));
            }
            case "STATUS_OBSERVATION" -> {
                fields(payload,Set.of("resourceType","resourceKey","observationType","value","quality","monitoringPointId","vendorCode","vendorMessage"),Set.of("resourceType","resourceKey","observationType","value","quality"));
                resource(payload,deviceType);String observation=text(payload,"observationType",32),value=text(payload,"value",32);
                require(VALUES.getOrDefault(observation,Set.of()).contains(value));require(Set.of("GOOD","UNCERTAIN","BAD").contains(text(payload,"quality",16)));
                boolean allowed=switch(deviceType) {
                    case "IPC" -> payload.path("resourceType").asText().equals("DEVICE")&&Set.of("OPERATING_MODE","VIDEO_SIGNAL").contains(observation);
                    case "NVR" -> (observation.equals("OPERATING_MODE")&&payload.path("resourceType").asText().equals("DEVICE"))||(observation.equals("RECORDING_STATUS")&&payload.path("resourceType").asText().equals("VIDEO_CHANNEL"));
                    case "SWITCH" -> (observation.equals("OPERATING_MODE")&&payload.path("resourceType").asText().equals("DEVICE"))||(Set.of("LINK_STATUS","POE_STATUS").contains(observation)&&payload.path("resourceType").asText().equals("SWITCH_PORT"));
                    default -> false;
                };require(allowed);optional(payload,"monitoringPointId",64); optional(payload,"vendorCode",64);optional(payload,"vendorMessage",256);
                require(stream.equals("STATUS/"+payload.path("resourceType").asText()+"/"+payload.path("resourceKey").asText()+"/"+observation));
            }
            case "DEVICE_FAULT","DEVICE_RECOVERED" -> {
                Set<String> allowed=new HashSet<>(Set.of("resourceType","resourceKey","faultCode","vendorCode","vendorMessage"));
                if(type.equals("DEVICE_RECOVERED"))allowed.addAll(Set.of("relatedFaultEventId","recoveryEvidence"));
                fields(payload,allowed,Set.of("resourceType","resourceKey","faultCode"));resource(payload,deviceType);
                String code=text(payload,"faultCode",64);require(FAULTS.contains(code));
                require(code.startsWith(switch(deviceType){case "IPC"->"CAMERA_";case "NVR"->"NVR_";case "SWITCH"->"SWITCH_";default->"INVALID_";}));
                String resource=payload.path("resourceType").asText();
                require(!code.startsWith("SWITCH_PORT_")&&!code.equals("SWITCH_POE_FAULT")||resource.equals("SWITCH_PORT"));
                require(!Set.of("NVR_RECORDING_STOPPED","NVR_CHANNEL_LOST").contains(code)||resource.equals("VIDEO_CHANNEL"));
                if(payload.has("relatedFaultEventId"))uuid(text(payload,"relatedFaultEventId",36));optional(payload,"recoveryEvidence",512);optional(payload,"vendorCode",64);optional(payload,"vendorMessage",256);
                require(stream.equals("EVENT/"+resource+"/"+payload.path("resourceKey").asText()));
            }
            default -> throw new IllegalArgumentException("Unsupported device event");
        }
        return new VideoDeviceMessage(root.path("eventId").asText(),1,device,session,type,stream,seq,occurred,payload);
    }
    private static void resource(JsonNode p,String deviceType) {
        String resource=text(p,"resourceType",32),key=text(p,"resourceKey",64);
        require(switch(resource){case "DEVICE"->key.equals("device");case "VIDEO_CHANNEL"->deviceType.equals("NVR")&&key.matches("channel:[1-9][0-9]*");case "SWITCH_PORT"->deviceType.equals("SWITCH")&&key.matches("port:[A-Za-z0-9._-]{1,48}");default->false;});
    }
    private static void fields(JsonNode node,Set<String> allowed,Set<String> required) {
        require(node.isObject());node.fieldNames().forEachRemaining(name->require(allowed.contains(name)));required.forEach(name->require(node.has(name)&&!node.path(name).isNull()));
    }
    private static String text(JsonNode node,String name,int max) {JsonNode value=node.path(name);require(value.isTextual()&&!value.asText().isEmpty()&&value.asText().length()<=max);return value.asText();}
    private static void optional(JsonNode node,String name,int max) {if(node.has(name)){require(node.path(name).isTextual()&&node.path(name).asText().length()<=max);}}
    private static void uuid(String value) {require(UUID.fromString(value).toString().equals(value.toLowerCase(Locale.ROOT)));}
    private static void require(boolean valid) {if(!valid)throw new IllegalArgumentException("Invalid device message");}
}
