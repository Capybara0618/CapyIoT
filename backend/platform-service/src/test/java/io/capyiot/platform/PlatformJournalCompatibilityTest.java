package io.capyiot.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformJournalCompatibilityTest {
    @Test void fingerprintMatchesTheLiteralLegacyCanonicalPayload()throws Exception {
        String key="test-stable-fingerprint-key-at-least-32-characters";
        var journal=new PlatformJournal(mock(JdbcTemplate.class),mock(TransactionTemplate.class),new ObjectMapper(),key);
        var input=Map.of("actor","USER-ADMIN","type","user.create","command",new PlatformService.CreateUser("new-user","test-password","New user",null));
        // Literal old format, including nullable contact and credential field; only its keyed digest is stored.
        String oldCanonical="{\"actor\":\"USER-ADMIN\",\"command\":{\"contact\":null,\"displayName\":\"New user\",\"loginName\":\"new-user\",\"password\":\"test-password\"},\"type\":\"user.create\"}";
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        assertThat(journal.fingerprint(input)).isEqualTo(HexFormat.of().formatHex(mac.doFinal(oldCanonical.getBytes(StandardCharsets.UTF_8))));
        assertThat(journal.fingerprint(input)).isNotEqualTo(new PlatformJournal(mock(JdbcTemplate.class),mock(TransactionTemplate.class),new ObjectMapper(),"different-stable-fingerprint-key-at-least-32-characters").fingerprint(input));
    }
    @Test void sameKeyWithDifferentActorOrBodyCannotReplayExistingReceipt() {
        var db=mock(JdbcTemplate.class);var journal=new PlatformJournal(db,mock(TransactionTemplate.class),new ObjectMapper(),"test-stable-fingerprint-key-at-least-32-characters");
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(java.util.List.of(Map.of("request_hash","a".repeat(64),"result_snapshot","{}")));
        assertThatThrownBy(()->journal.replay("old-key","another-user","user.create",Map.of("loginName","changed")))
                .isInstanceOfSatisfying(ApiProblem.class,error->assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }
}
