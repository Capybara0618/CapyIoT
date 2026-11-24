CREATE TABLE device_authentication_attempt (
 identity_hash CHAR(64) PRIMARY KEY,failed_count INT UNSIGNED NOT NULL DEFAULT 0,locked_until DATETIME(3),
 last_failed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),INDEX ix_auth_attempt_cleanup(last_failed_at)
) ENGINE=InnoDB;
