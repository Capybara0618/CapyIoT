package io.capyiot.device.video;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="capyiot.assets.enabled",havingValue="true")
public class DeviceSessions {
    private final AssetStore store;
    private final DeviceSigningKeys keys;
    private final AssetService assets;
    private final CommandJournal commands;
    private final LocalEvents events;
    private final Pbkdf2PasswordEncoder passwords=Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final String dummyHash=passwords.encode(UUID.randomUUID().toString());
    public DeviceSessions(AssetStore store,DeviceSigningKeys keys,AssetService assets,ObjectMapper json,@Value("${capyiot.device.command-fingerprint-key}") String key) {
        this.store=store;this.keys=keys;this.assets=assets;commands=new CommandJournal(store.db,store.tx,json,key);events=new LocalEvents(store.db,json,"device");
    }
    public record SessionCommand(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String deviceId,@NotBlank @Size(max=256) String secret,
                                 @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String sessionId) { @Override public String toString(){return "SessionCommand[REDACTED]";} }
    public record SessionResult(long sessionEpoch,long credentialVersion,String sessionToken,Instant expiresAt) { @Override public String toString(){return "SessionResult[REDACTED]";} }
    public record SessionContext(String deviceId,String parkId,String deviceType,String source,String sessionId,long sessionEpoch,long credentialVersion,Instant expiresAt,String monitoringPointId) {}
    public record Rotate(@NotNull @Min(0) Long expectedVersion,@NotBlank @Size(max=1000) String reason) {}
    public SessionResult create(SessionCommand c) {
        String identity=identity(c.deviceId());
        if(Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM device_authentication_attempt WHERE identity_hash=? AND locked_until>CURRENT_TIMESTAMP(3)",Boolean.class,identity)))throw new ApiProblem(429,"DEVICE_AUTH_RATE_LIMITED","设备认证失败次数过多，请稍后再试");
        try{return store.tx.execute(status->{
            var rows=store.db.queryForList("SELECT * FROM device WHERE id=? FOR UPDATE",c.deviceId());
            if(rows.isEmpty()){passwords.matches(c.secret(),dummyHash);throw denied();}
            var d=rows.getFirst(); String hash=Objects.toString(d.get("device_secret_hash"),dummyHash);
            boolean valid=passwords.matches(c.secret(),hash);
            if(!valid||!"IN_SERVICE".equals(d.get("asset_status"))||Boolean.TRUE.equals(d.get("legacy_only")))throw denied();
            long credential=((Number)d.get("credential_version")).longValue(),epoch=((Number)d.get("session_epoch")).longValue();
            var sessions=store.db.queryForList("SELECT * FROM device_session WHERE device_id=? AND session_id=? FOR UPDATE",c.deviceId(),c.sessionId());
            Instant expiry=Instant.now().plusSeconds(900);
            if(!sessions.isEmpty()) {
                var previous=sessions.getFirst();
                if(previous.get("status").equals("REVOKED")||((Number)previous.get("credential_version")).longValue()!=credential||!Objects.equals(d.get("active_session_id"),c.sessionId())) throw denied();
                epoch=((Number)previous.get("session_epoch")).longValue();
                store.db.update("UPDATE device_session SET status='ACTIVE',expires_at=? WHERE device_id=? AND session_id=?",DbTime.sql(expiry),c.deviceId(),c.sessionId());
            } else {
                store.db.update("UPDATE device_session SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP(3),revoked_reason='NEW_SESSION' WHERE device_id=? AND status IN ('ACTIVE','EXPIRED')",c.deviceId());
                epoch++;
                store.db.update("INSERT INTO device_session(device_id,session_id,session_epoch,credential_version,status,expires_at) VALUES(?,?,?,?,'ACTIVE',?)",c.deviceId(),c.sessionId(),epoch,credential,DbTime.sql(expiry));
                store.db.update("UPDATE device SET session_epoch=?,active_session_id=? WHERE id=?",epoch,c.sessionId(),c.deviceId());
            }
            // A disconnect request from an older epoch must be drained before a new valid CONNECT.
            if(Boolean.TRUE.equals(store.db.queryForObject("SELECT COUNT(*)>0 FROM broker_disconnect_outbox WHERE device_id=? AND status='PENDING'",Boolean.class,c.deviceId()))) throw new ApiProblem(503,"DEVICE_RECONNECT_PENDING","旧连接撤销尚未完成，请稍后重试");
            store.db.update("DELETE FROM device_authentication_attempt WHERE identity_hash=?",identity);
            return new SessionResult(epoch,credential,token(c.deviceId(),c.sessionId(),epoch,credential,expiry),expiry);
        });}catch(ApiProblem failure){
            if(failure.status()==401)store.tx.executeWithoutResult(status->{
                store.db.update("INSERT INTO device_authentication_attempt(identity_hash,failed_count) VALUES(?,1) ON DUPLICATE KEY UPDATE failed_count=IF(last_failed_at<TIMESTAMPADD(MINUTE,-10,CURRENT_TIMESTAMP(3)),1,failed_count+1),last_failed_at=CURRENT_TIMESTAMP(3)",identity);
                store.db.update("UPDATE device_authentication_attempt SET locked_until=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP(3)) WHERE identity_hash=? AND failed_count>=5",identity);
                var rows=store.db.queryForList("SELECT campus_id FROM device WHERE id=?",c.deviceId());
                if(!rows.isEmpty())events.append("DeviceAuthenticationFailed",c.deviceId(),rows.getFirst().get("campus_id").toString(),null,Map.of("reason","DEVICE_CREDENTIAL_OR_SESSION_REJECTED"));
            });
            throw failure;
        }
    }
    private static String identity(String id){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000)
    void cleanAttempts(){store.db.update("DELETE FROM device_authentication_attempt WHERE last_failed_at<TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP(3)) AND (locked_until IS NULL OR locked_until<CURRENT_TIMESTAMP(3))");}
    private String token(String device,String session,long epoch,long credential,Instant expiry) {
        String topic="capyiot/v1/devices/"+device+"/sessions/"+session+"/events";
        return keys.sign(JwtClaimsSet.builder().issuer("capyiot-device-service").subject(device).audience(List.of("capyiot-emqx"))
                .issuedAt(Instant.now()).expiresAt(expiry).id(UUID.randomUUID().toString()).claim("sid",session).claim("sessionEpoch",epoch).claim("credentialVersion",credential)
                .claim("username",device).claim("clientid",device).claim("acl",List.of(
                        Map.of("permission","allow","action","publish","topic",topic,"qos",List.of(1),"retain",false),
                        Map.of("permission","deny","action","all","topic","#"))).build());
    }
    public SessionContext current(String device,String session) {
        var rows=store.db.queryForList("""
            SELECT d.*,s.session_id,s.expires_at FROM device d JOIN device_session s ON s.device_id=d.id
             AND s.session_id=d.active_session_id AND s.session_epoch=d.session_epoch AND s.credential_version=d.credential_version
            WHERE d.id=? AND s.session_id=? AND s.status='ACTIVE' AND s.expires_at>CURRENT_TIMESTAMP(3)
             AND d.asset_status='IN_SERVICE' AND NOT d.legacy_only
            """,device,session);
        if(rows.isEmpty())throw denied(); var d=rows.getFirst();
        var points=store.db.queryForList("SELECT monitoring_point_id FROM monitoring_point_binding WHERE ipc_device_id=? AND valid_to IS NULL",String.class,device);
        return new SessionContext(device,d.get("campus_id").toString(),d.get("device_type").toString(),d.get("managed_source").toString(),session,
                ((Number)d.get("session_epoch")).longValue(),((Number)d.get("credential_version")).longValue(),DbTime.instant(d.get("expires_at")),points.isEmpty()?null:points.getFirst());
    }
    public JsonNode rotate(AuthorizationContext actor,String key,String id,Rotate c) {
        var device=assets.requireVisibleDevices(actor,"device.manage",List.of(id)).getFirst();
        return commands.execute(device.parkId(),key,actor.userId(),"DeviceCredentialRotated",Map.of("deviceId",id,"body",c),()->{
            var d=store.db.queryForMap("SELECT version,credential_version,session_epoch FROM device WHERE id=? FOR UPDATE",id);
            if(((Number)d.get("version")).longValue()!=c.expectedVersion())throw ApiProblem.conflict("设备已变更");
            byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);String hash=passwords.encode(secret);
            long credential=((Number)d.get("credential_version")).longValue()+1,epoch=((Number)d.get("session_epoch")).longValue()+1;
            store.db.update("UPDATE device SET password_hash=?,device_secret_hash=?,credential_version=?,credential_rotated_at=CURRENT_TIMESTAMP(3),active_session_id=NULL,session_epoch=?,version=version+1 WHERE id=?",hash,hash,credential,epoch,id);
            store.db.update("UPDATE device_session SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP(3),revoked_reason='CREDENTIAL_ROTATED' WHERE device_id=? AND status<>'REVOKED'",id);
            store.db.update("INSERT INTO broker_disconnect_outbox(device_id,expected_session_epoch) VALUES(?,?)",id,epoch);
            events.append("DeviceCredentialRotated",id,device.parkId(),actor,Map.of("credentialVersion",credential,"reason",c.reason()));
            return Map.of("deviceId",id,"credentialVersion",credential,"version",c.expectedVersion()+1,"deviceSecret",secret,"rotationId",UUID.randomUUID().toString(),"rotatedAt",Instant.now());
        });
    }
    public String ingestionToken(String clientId) {
        if(!clientId.matches("capyiot-ingestion-[A-Za-z0-9_-]{1,48}"))throw ApiProblem.invalid("接入服务 clientId 无效");
        return keys.sign(JwtClaimsSet.builder().issuer("capyiot-device-service").subject("device-ingestion").audience(List.of("capyiot-emqx"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).id(UUID.randomUUID().toString())
                .claim("username","device-ingestion").claim("clientid",clientId).claim("acl",List.of(
                        Map.of("permission","allow","action","subscribe","topic","capyiot/v1/devices/+/sessions/+/events","qos",List.of(1)),
                        Map.of("permission","deny","action","all","topic","#"))).build());
    }
    private ApiProblem denied(){return new ApiProblem(401,"DEVICE_AUTHENTICATION_FAILED","设备凭据或会话无效");}
}
