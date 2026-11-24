ALTER TABLE device_session ADD credential_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
 ADD status VARCHAR(16) NOT NULL DEFAULT 'REVOKED', ADD expires_at DATETIME(3), ADD revoked_at DATETIME(3),
 ADD revoked_reason VARCHAR(1000),
 ADD active_marker TINYINT GENERATED ALWAYS AS(CASE WHEN status='ACTIVE' THEN 1 ELSE NULL END) STORED,
 ADD UNIQUE KEY uk_device_active_session(device_id,active_marker), ADD CHECK(status IN ('ACTIVE','REVOKED','EXPIRED'));
-- Old HS256 sessions are historical metadata only and may never be reactivated.
UPDATE device_session SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP(3),revoked_reason='A09_RS256_CUTOVER',expires_at=created_at;
UPDATE device SET active_session_id=NULL;
CREATE TABLE broker_disconnect_outbox (
 disconnect_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, device_id VARCHAR(64) NOT NULL,
 expected_session_epoch BIGINT UNSIGNED NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempt_count INT NOT NULL DEFAULT 0, next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), completed_at DATETIME(3),
 UNIQUE KEY uk_disconnect_epoch(device_id,expected_session_epoch), FOREIGN KEY(device_id) REFERENCES device(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
