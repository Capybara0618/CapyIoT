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
    BrokerDisconnects(AssetStore store,@Value("${EMQX_MANAGEMENT_URL}") String url,@Value("${EMQX_API_KEY}") String key,@Value("${EMQX_API_SECRET}") String secret){
        this.store=store;var request=new SimpleClientHttpRequestFactory();request.setConnectTimeout(Duration.ofSeconds(2));request.setReadTimeout(Duration.ofSeconds(3));
        broker=RestClient.builder().baseUrl(url).requestFactory(request).defaultHeaders(h->h.setBasicAuth(key,secret)).build();
    }
    @Scheduled(fixedDelay=2000) void tick(){
        for(var row:store.db.queryForList("SELECT * FROM broker_disconnect_outbox WHERE status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(3) ORDER BY disconnect_id LIMIT 20")) {
            try {
                // Session issuance refuses while this durable revocation request is pending.
                try{broker.delete().uri("/api/v5/clients/{id}",row.get("device_id")).retrieve().toBodilessEntity();}catch(HttpClientErrorException.NotFound ignored){}
                store.db.update("UPDATE broker_disconnect_outbox SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(3) WHERE disconnect_id=?",row.get("disconnect_id"));
            }catch(RestClientException failure){store.db.update("UPDATE broker_disconnect_outbox SET attempt_count=attempt_count+1,next_attempt_at=TIMESTAMPADD(SECOND,5,CURRENT_TIMESTAMP(3)) WHERE disconnect_id=?",row.get("disconnect_id"));}
        }
    }
}
