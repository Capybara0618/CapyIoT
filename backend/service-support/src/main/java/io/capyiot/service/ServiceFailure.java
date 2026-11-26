package io.capyiot.service;

import io.capyiot.contract.internal.ServiceId;

/** Deliberately excludes raw HTTP bodies, credentials and upstream exception messages. */
public final class ServiceFailure extends RuntimeException {
    public enum Outcome { NOT_STARTED, REJECTED, UNKNOWN }
    private final int status;
    private final String code;
    private final ServiceId dependency;
    private final boolean retryable;
    private final Outcome outcome;

    public ServiceFailure(int status,String code,ServiceId dependency,boolean retryable,Outcome outcome) {
        super("Service operation unavailable: "+code);
        this.status=status;this.code=code;this.dependency=dependency;this.retryable=retryable;this.outcome=outcome;
    }
    public int status(){return status;}
    public String code(){return code;}
    public ServiceId dependency(){return dependency;}
    public boolean retryable(){return retryable;}
    public Outcome outcome(){return outcome;}
    public ServiceFailure at(ServiceId target){return dependency==null?new ServiceFailure(status,code,target,retryable,outcome):this;}
    public ServiceFailure unknown(){return new ServiceFailure(status,code,dependency,false,Outcome.UNKNOWN);}
    public static ServiceFailure unavailable(String code,ServiceId target,boolean retryable) {
        return new ServiceFailure(503,code,target,retryable,Outcome.NOT_STARTED);
    }
    public static ServiceFailure denied(int status,String code) {
        return new ServiceFailure(status,code,null,false,Outcome.REJECTED);
    }
}
