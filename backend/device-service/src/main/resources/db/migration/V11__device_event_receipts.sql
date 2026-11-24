CREATE TABLE device_event_receipt (
 source VARCHAR(16) NOT NULL, event_id VARCHAR(64) NOT NULL, envelope_hash CHAR(64) NOT NULL,
 device_id VARCHAR(64) NOT NULL, processing_status VARCHAR(32) NOT NULL, received_at DATETIME(3) NOT NULL,
 PRIMARY KEY(source,event_id), INDEX ix_receipt_cleanup(received_at), FOREIGN KEY(device_id) REFERENCES device(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
