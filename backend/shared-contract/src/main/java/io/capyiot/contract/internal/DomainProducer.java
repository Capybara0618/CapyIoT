package io.capyiot.contract.internal;

/** Source context remains stable when its owning process changes. */
public enum DomainProducer {
    DEVICE("device",ServiceId.DEVICE), FAULT("fault",ServiceId.FAULT),
    WORKORDER("workorder",ServiceId.WORKFLOW), PLATFORM("platform",ServiceId.PLATFORM);

    private final String context;
    private final ServiceId service;
    DomainProducer(String context,ServiceId service){this.context=context;this.service=service;}
    public String context(){return context;}
    public String producer(){return service.value();}
    public String topic(){return "capyiot.domain."+context+".v1";}

    public boolean accepts(String topic,String context,String producer,boolean allowHistoricalCore) {
        return topic().equals(topic) && this.context.equals(context)
                && (producer().equals(producer) || allowHistoricalCore && "core-service".equals(producer));
    }
}
