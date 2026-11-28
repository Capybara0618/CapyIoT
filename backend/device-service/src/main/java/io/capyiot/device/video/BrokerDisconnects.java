package io.capyiot.device.video;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
@ConditionalOnProperty(name="capyiot.device-runtime.enabled",havingValue="true")
class BrokerDisconnects {
    private final AssetStore store;private final RestClient broker;
    @org.springframework.beans.factory.annotation.Autowired
    BrokerDisconnects(AssetStore store,@Value("${EMQX_MANAGEMENT_URL}") String url,@Value("${EMQX_API_KEY}") String key,@Value("${EMQX_API_SECRET}") String secret){
        this.store=store;var request=new SimpleClientHttpRequestFactory();request.setConnectTimeout(Duration.ofSeconds(2));request.setReadTimeout(Duration.ofSeconds(3));
        broker=RestClient.builder().baseUrl(url).requestFactory(request).defaultHeaders(h->h.setBasicAuth(key,secret)).build();
    }
    BrokerDisconnects(AssetStore store,RestClient broker){this.store=store;this.broker=broker;}
    @Scheduled(fixedDelay=2000) void tick(){
        if(io.capyiot.contract.RuntimeGate.draining())return;
        // Claim once. A process crash or transport timeout leaves an explicit unknown outcome.
        // Reclaiming by timeout alone could let a delayed DELETE kill a newer valid session.
        for(int i=0;i<20;i++) {
            String token=java.util.UUID.randomUUID().toString();
            var row=store.tx.execute(status->{
                var rows=store.db.queryForList("SELECT * FROM broker_disconnect_outbox WHERE status='PENDING' AND delivery_token IS NULL AND outcome_unknown=FALSE AND next_attempt_at<=CURRENT_TIMESTAMP(3) ORDER BY disconnect_id LIMIT 1 FOR UPDATE SKIP LOCKED");
                if(rows.isEmpty())return null;
                var item=rows.getFirst();
                store.db.update("UPDATE broker_disconnect_outbox SET delivery_token=?,delivery_started_at=CURRENT_TIMESTAMP(3),attempt_count=attempt_count+1 WHERE disconnect_id=?",token,item.get("disconnect_id"));
                return item;
            });
            if(row==null)return;
            try {
                // Session issuance refuses while this durable revocation request is pending.
                try{broker.delete().uri("/api/v5/clients/{id}",row.get("device_id")).retrieve().toBodilessEntity();}catch(HttpClientErrorException.NotFound ignored){}
                store.db.update("UPDATE broker_disconnect_outbox SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(3),delivery_token=NULL WHERE disconnect_id=? AND delivery_token=?",row.get("disconnect_id"),token);
            }catch(RestClientException failure){
                store.db.update("UPDATE broker_disconnect_outbox SET outcome_unknown=TRUE WHERE disconnect_id=? AND delivery_token=?",row.get("disconnect_id"),token);
            }
        }
    }
}
