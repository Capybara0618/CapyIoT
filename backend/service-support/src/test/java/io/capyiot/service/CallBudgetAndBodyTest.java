package io.capyiot.service;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CallBudgetAndBodyTest {
    final Clock clock=Clock.fixed(Instant.parse("2026-11-26T06:00:00Z"),ZoneOffset.UTC);

    @Test void incomingDeadlineCanOnlyShortenTheLocalBudget() {
        String late=Long.toString(clock.instant().plusSeconds(60).toEpochMilli());
        var bounded=CallBudget.receive(late,Duration.ofSeconds(10),clock);
        assertThat(bounded.deadline()).isEqualTo(clock.instant().plusSeconds(10));
        String early=Long.toString(clock.instant().plusSeconds(2).toEpochMilli());
        var shortBudget=CallBudget.receive(early,Duration.ofSeconds(10),clock);
        assertThat(shortBudget.remaining(Duration.ofSeconds(3))).isLessThanOrEqualTo(Duration.ofSeconds(2));
        assertThatThrownBy(()->CallBudget.receive("-1",Duration.ofSeconds(10),clock)).isInstanceOf(ServiceFailure.class);
        assertThatThrownBy(()->CallBudget.start(Duration.ofSeconds(11),clock)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void oversizedStreamingBodyCancelsBeforeBeingAccumulated() {
        var cancelled=new AtomicBoolean();var body=new JdkServiceTransport.LimitedBody(4);
        body.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){cancelled.set(true);}});
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2,3})));body.onNext(List.of(ByteBuffer.wrap(new byte[]{4,5})));
        assertThat(cancelled).isTrue();assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
    }

    @Test void interruptedCallerDoesNotReceiveANewBudgetOrRetry() {
        var budget=CallBudget.start(Duration.ofSeconds(10),clock);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(budget::check).isInstanceOfSatisfying(ServiceFailure.class,
                    error->assertThat(error.code()).isEqualTo("CALL_CANCELLED"));
        }finally{Thread.interrupted();}
    }
}
