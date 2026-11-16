CREATE INDEX ix_outbox_stream ON outbox_event(topic,message_key,status,id);
