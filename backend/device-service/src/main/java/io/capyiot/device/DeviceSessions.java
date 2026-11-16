package io.capyiot.device;

import static io.capyiot.device.DeviceModels.*;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeviceSessions {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;

    DeviceSessions(JdbcTemplate jdbc, PasswordEncoder passwords, @Value("${capyiot.device-jwt-secret}") String secret) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        byte[] bytes = Base64.getDecoder().decode(secret);
        if (bytes.length < 32) throw new IllegalArgumentException("Device signing key is too short");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(bytes));
    }

    @Transactional
    public SessionResult open(SessionRequest request) {
        var rows = jdbc.queryForList("SELECT * FROM device WHERE id=? FOR UPDATE", request.deviceId());
        if (rows.isEmpty()) throw invalidCredentials();
        var device = rows.getFirst();
        if (!passwords.matches(request.secret(), (String) device.get("password_hash"))) throw invalidCredentials();
        if (!Boolean.TRUE.equals(device.get("enabled"))) throw DeviceProblem.conflict("设备已停用");
        long epoch = ((Number) device.get("session_epoch")).longValue();
        if (!request.sessionId().equals(device.get("active_session_id"))) {
            if (!jdbc.queryForList("SELECT session_epoch FROM device_session WHERE device_id=? AND session_id=?",
                    request.deviceId(), request.sessionId()).isEmpty()) throw DeviceProblem.conflict("历史设备会话已失效");
            if (epoch >= 9007199254740991L) throw DeviceProblem.conflict("设备会话版本已达上限");
            epoch++;
            jdbc.update("INSERT INTO device_session(device_id,session_id,session_epoch) VALUES (?,?,?)",
                    request.deviceId(), request.sessionId(), epoch);
            jdbc.update("UPDATE device SET active_session_id=?,session_epoch=? WHERE id=?", request.sessionId(), epoch, request.deviceId());
        }
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(86400);
        var claims = JwtClaimsSet.builder().issuer("capyiot-device-service").audience(List.of("capyiot-ingestion"))
                .subject(request.deviceId()).issuedAt(now).expiresAt(expires)
                .claim("campusId", device.get("campus_id")).claim("sessionId", request.sessionId())
                .claim("sessionEpoch", epoch).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new SessionResult(epoch, token, expires);
    }

    private DeviceProblem invalidCredentials() {
        return new DeviceProblem(HttpStatus.UNAUTHORIZED, "DEVICE_AUTH_FAILED", "设备凭据无效");
    }
}
