package io.capyiot.device.video;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.capyiot.contract.internal.ServiceId;
import io.capyiot.service.CallBudget;
import io.capyiot.service.ServiceFailure;
import io.capyiot.service.ServiceTokenVerifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.jwt.*;

/** Verifies signatures and token purpose. Current session/grants are loaded only from platform_db afterwards. */
final class DeviceJwtDecoder implements JwtDecoder {
    private final ServiceTokenVerifier.Keys keys;private final Clock clock;
    DeviceJwtDecoder(ServiceTokenVerifier.Keys keys,Clock clock){this.keys=keys;this.clock=clock;}
    public Jwt decode(String token) {
        try {
            if(token==null||token.length()>16384)throw invalid();
            var signed=SignedJWT.parse(token);var header=signed.getHeader();var claims=signed.getJWTClaimsSet();
            boolean agent="capyiot-agent-service".equals(claims.getIssuer());
            if(!agent&&!"capyiot-gateway".equals(claims.getIssuer()))throw invalid();
            if(!JWSAlgorithm.RS256.equals(header.getAlgorithm())||header.getKeyID()==null||!header.getKeyID().matches("[A-Za-z0-9_-]{1,128}"))throw invalid();
            var key=keys.resolve(agent?ServiceId.AGENT:ServiceId.GATEWAY,header.getKeyID(),RequestContextBudget.current(clock));
            if(key==null||!signed.verify(new RSASSAVerifier(key)))throw invalid();
            Instant now=clock.instant();var issued=claims.getIssueTime();var expires=claims.getExpirationTime();
            if(issued==null||expires==null||!expires.toInstant().isAfter(now)||issued.toInstant().isAfter(now.plusSeconds(5))
                    ||claims.getNotBeforeTime()!=null&&claims.getNotBeforeTime().toInstant().isAfter(now.plusSeconds(5))
                    ||!expires.after(issued)||claims.getSubject()==null||claims.getJWTID()==null
                    ||!claims.getJWTID().matches("[A-Za-z0-9_-]{1,128}"))throw invalid();
            if(agent) {
                if(!"capyiot-agent-service".equals(claims.getSubject())||!"CREATE_WORK_ORDER".equals(claims.getStringClaim("command"))
                        ||!(List.of("capyiot-workflow").equals(claims.getAudience())||List.of("capyiot-core").equals(claims.getAudience()))
                        ||Duration.between(issued.toInstant(),expires.toInstant()).compareTo(Duration.ofSeconds(120))>0)throw invalid();
                for(String name:List.of("actorUserId","confirmationId","draftId"))if(claims.getStringClaim(name)==null||!claims.getStringClaim(name).matches("[A-Za-z0-9_-]{1,64}"))throw invalid();
                Object revision=claims.getClaim("draftRevision");if(!(revision instanceof Number n)||n.longValue()<1||n.doubleValue()!=n.longValue())throw invalid();
                if(claims.getStringClaim("requestHash")==null||!claims.getStringClaim("requestHash").matches("[a-f0-9]{64}")
                        ||claims.getStringClaim("idempotencyKey")==null||!claims.getStringClaim("idempotencyKey").matches("agent-[A-Za-z0-9_-]{1,64}"))throw invalid();
            } else if(!List.of("capyiot-api").equals(claims.getAudience())||!claims.getSubject().matches("[A-Za-z0-9_-]{1,64}"))throw invalid();
            Map<String,Object> values=new java.util.HashMap<>(claims.getClaims());values.put("iat",issued.toInstant());values.put("exp",expires.toInstant());
            return new Jwt(token,issued.toInstant(),expires.toInstant(),header.toJSONObject(),values);
        }catch(ServiceFailure unavailable){throw unavailable;}
        catch(Exception error){throw invalid();}
    }
    private static BadJwtException invalid(){return new BadJwtException("Actor token invalid");}
    static final class RequestContextBudget {
        static CallBudget current(Clock clock) {
            if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()!=null)return RequestCalls.budget();
            return CallBudget.start(Duration.ofSeconds(10),clock);
        }
    }
}
