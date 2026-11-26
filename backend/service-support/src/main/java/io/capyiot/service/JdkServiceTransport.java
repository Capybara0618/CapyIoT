package io.capyiot.service;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

/** Bounds connection, full response body, cancellation and the whole call; never follows redirects. */
public final class JdkServiceTransport implements ServiceTransport,AutoCloseable {
    public static final int MAX_RESPONSE_BYTES=1_048_576;
    private final HttpClient client;
    public JdkServiceTransport(SSLContext tls) {
        var parameters=new SSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setProtocols(new String[]{"TLSv1.3","TLSv1.2"});
        client=HttpClient.newBuilder().sslContext(java.util.Objects.requireNonNull(tls)).sslParameters(parameters)
                .connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).build();
    }
    @Override public Result exchange(HttpRequest request,CallBudget budget) {
        ServiceUris.https(request.uri(),false);
        var timeout=budget.remaining(Duration.ofSeconds(3));
        CompletableFuture<HttpResponse<byte[]>> future;
        try { future=client.sendAsync(request,info->new LimitedBody(MAX_RESPONSE_BYTES)); }
        catch(RuntimeException error){throw ServiceFailure.unavailable("DEPENDENCY_UNAVAILABLE",null,false);}
        try {
            var response=future.get(timeout.toNanos(),TimeUnit.NANOSECONDS);
            budget.check();return new Result(response.statusCode(),response.body());
        }catch(InterruptedException error) {
            future.cancel(true);Thread.currentThread().interrupt();
            throw ServiceFailure.unavailable("CALL_CANCELLED",null,false);
        }catch(TimeoutException error) {
            future.cancel(true);throw ServiceFailure.unavailable("DEPENDENCY_TIMEOUT",null,true);
        }catch(ExecutionException|CancellationException error) {
            future.cancel(true);
            Throwable cause=error;
            for(int depth=0;cause!=null&&depth<16;depth++,cause=cause.getCause()) {
                if(cause instanceof SSLException)throw ServiceFailure.unavailable("DEPENDENCY_TLS_INVALID",null,false);
                if(cause instanceof ResponseTooLarge)throw ServiceFailure.unavailable("DEPENDENCY_RESPONSE_TOO_LARGE",null,false);
            }
            throw ServiceFailure.unavailable(error instanceof CancellationException?"CALL_CANCELLED":"DEPENDENCY_UNAVAILABLE",null,
                    !(error instanceof CancellationException));
        }
    }
    @Override public void close(){client.close();}

    private static final class ResponseTooLarge extends RuntimeException {}
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private final long maximum;
        private Flow.Subscription subscription;
        private long received;
        private boolean failed;
        LimitedBody(long maximum){this.maximum=maximum;}
        @Override public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        @Override public void onSubscribe(Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
        @Override public void onNext(List<ByteBuffer> items) {
            if(failed)return;
            for(var item:items)received+=item.remaining();
            if(received>maximum){failed=true;subscription.cancel();delegate.onError(new ResponseTooLarge());return;}
            delegate.onNext(items);
        }
        @Override public void onError(Throwable error){if(!failed){failed=true;delegate.onError(error);}}
        @Override public void onComplete(){if(!failed)delegate.onComplete();}
    }
}
