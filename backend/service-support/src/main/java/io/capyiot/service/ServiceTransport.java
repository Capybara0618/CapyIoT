package io.capyiot.service;

import java.net.http.HttpRequest;

@FunctionalInterface
public interface ServiceTransport {
    record Result(int status,byte[] body) {
        public Result { body=body.clone(); }
        @Override public byte[] body(){return body.clone();}
        @Override public String toString(){return "HttpResult[status="+status+", bytes="+body.length+"]";}
    }
    Result exchange(HttpRequest request,CallBudget budget);
}
