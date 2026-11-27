package io.capyiot.legacyworkflow;

import io.capyiot.contract.AlarmEvent;
import io.capyiot.contract.ApiResponse;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
class AlarmSourceClient {
    private final RestClient alarm;

    AlarmSourceClient(RestClient.Builder builder,
            @Value("${capyiot.alarm-service-url:http://localhost:8083}") String alarmUrl) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.alarm = builder.baseUrl(alarmUrl).requestFactory(factory).build();
    }

    AlarmEvent.Source get(long id, String campus, String token) {
        try {
            var response = alarm.get().uri("/api/v1/alarms/{id}/workflow-source", id)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve().body(new ParameterizedTypeReference<ApiResponse<AlarmEvent.Source>>() {});
            var source = response == null ? null : response.data();
            if (source == null || source.id() != id || !campus.equals(source.campusId())) {
                throw unavailable();
            }
            return source;
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404) throw WorkOrderProblem.missing("来源告警不存在");
            if (failure.getStatusCode().value() == 403) throw WorkOrderProblem.forbidden("无法读取该园区的告警");
            throw unavailable();
        } catch (RestClientException failure) {
            throw unavailable();
        }
    }

    private static WorkOrderProblem unavailable() {
        return new WorkOrderProblem(HttpStatus.SERVICE_UNAVAILABLE, "ALARM_SERVICE_UNAVAILABLE",
                "暂时无法核对来源告警，请稍后重试", Map.of());
    }
}
