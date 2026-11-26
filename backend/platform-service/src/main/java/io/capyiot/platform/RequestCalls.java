package io.capyiot.platform;

import io.capyiot.service.ActorCredential;
import io.capyiot.service.CallBudget;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

final class RequestCalls {
    private RequestCalls(){}
    static jakarta.servlet.http.HttpServletRequest request() {
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))
            throw new IllegalStateException("Resource operation requires a request context");
        return attributes.getRequest();
    }
    static CallBudget budget() {
        var request=request();Object current=request.getAttribute("capyiot.call-budget");
        if(current instanceof CallBudget budget)return budget;
        var budget=CallBudget.start(Duration.ofSeconds(10),Clock.systemUTC());request.setAttribute("capyiot.call-budget",budget);return budget;
    }
    static ActorCredential actor(){return ActorCredential.bearer(request().getHeader("Authorization"));}
    static String correlation() {
        var request=request();Object existing=request.getAttribute("capyiot.correlation");if(existing!=null)return existing.toString();
        String id=request.getHeader("X-Correlation-Id");if(id==null||!id.matches("[A-Za-z0-9_-]{1,128}"))id=UUID.randomUUID().toString();
        request.setAttribute("capyiot.correlation",id);return id;
    }
}
