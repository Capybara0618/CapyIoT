-- An uncertain external disconnect must not be retried across a newer valid session.
ALTER TABLE broker_disconnect_outbox
 ADD delivery_token VARCHAR(36), ADD delivery_started_at DATETIME(3),
 ADD outcome_unknown BOOLEAN NOT NULL DEFAULT FALSE;
