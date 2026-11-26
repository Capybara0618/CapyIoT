package io.capyiot.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** One deadline across retries and nested calls. Monotonic time prevents wall-clock rollback extending it. */
public final class CallBudget {
    public static final Duration MAXIMUM=Duration.ofSeconds(10);
    private final Clock clock;
    private final Instant deadline;
    private final long expiresNanos;

    private CallBudget(Clock clock,Instant deadline) {
        this.clock=clock;this.deadline=deadline;
        long nanos=Duration.between(clock.instant(),deadline).toNanos();
        if(nanos<=0)throw ServiceFailure.unavailable("DEPENDENCY_TIMEOUT",null,false);
        this.expiresNanos=System.nanoTime()+Math.min(nanos,MAXIMUM.toNanos());
    }
    public static CallBudget start(Duration limit,Clock clock) {
        if(limit==null||limit.isZero()||limit.isNegative()||limit.compareTo(MAXIMUM)>0)
            throw new IllegalArgumentException("Call budget must be within ten seconds");
        return new CallBudget(clock,clock.instant().plus(limit));
    }
    public static CallBudget receive(String header,Duration localLimit,Clock clock) {
        if(header==null||!header.matches("[0-9]{1,16}"))throw ServiceFailure.denied(400,"DEADLINE_INVALID");
        if(localLimit==null||localLimit.isNegative()||localLimit.isZero()||localLimit.compareTo(MAXIMUM)>0)
            throw new IllegalArgumentException("Invalid local request budget");
        try {
            Instant incoming=Instant.ofEpochMilli(Long.parseLong(header));
            Instant local=clock.instant().plus(localLimit);
            return new CallBudget(clock,incoming.isBefore(local)?incoming:local);
        }catch(NumberFormatException|java.time.DateTimeException error){throw ServiceFailure.denied(400,"DEADLINE_INVALID");}
    }
    public Instant deadline(){return deadline;}
    public String header(){return Long.toString(deadline.toEpochMilli());}
    public Duration remaining(Duration maximum) {
        if(maximum==null||maximum.isNegative()||maximum.isZero())throw new IllegalArgumentException("Invalid timeout bound");
        if(Thread.currentThread().isInterrupted())throw ServiceFailure.unavailable("CALL_CANCELLED",null,false);
        long nanos=Math.min(Duration.between(clock.instant(),deadline).toNanos(),expiresNanos-System.nanoTime());
        if(nanos<=0)throw ServiceFailure.unavailable("DEPENDENCY_TIMEOUT",null,false);
        return Duration.ofNanos(Math.min(nanos,maximum.toNanos()));
    }
    public void check(){remaining(MAXIMUM);}
}
