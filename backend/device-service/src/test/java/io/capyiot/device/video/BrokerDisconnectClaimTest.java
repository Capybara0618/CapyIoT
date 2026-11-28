package io.capyiot.device.video;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import java.util.Map;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.mockito.Mockito.*;

class BrokerDisconnectClaimTest {
    @Test void noClaimMeansNoExternalSideEffect() {
        var db = mock(JdbcTemplate.class);
        var tx = mock(TransactionTemplate.class);
        var store = new AssetStore(db, tx);
        new BrokerDisconnects(store, "http://127.0.0.1:1", "test", "test").tick();
        verifyNoInteractions(db);
    }
    @Test void unknownDisconnectIsNotCompletedOrRetried() {
        var db = mock(JdbcTemplate.class);
        var tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenReturn(Map.of("device_id", "ipc1", "disconnect_id", 1L)).thenReturn(null);
        var builder = RestClient.builder().baseUrl("http://broker");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://broker/api/v5/clients/ipc1")).andRespond(withServerError());
        new BrokerDisconnects(new AssetStore(db,tx),builder.build()).tick();
        verify(db).update(eq("UPDATE broker_disconnect_outbox SET outcome_unknown=TRUE WHERE disconnect_id=? AND delivery_token=?"),eq(1L),anyString());
        verifyNoMoreInteractions(db);
        server.verify();
    }
}
