CREATE TABLE command_lock (scope_id VARCHAR(64) PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO command_lock(scope_id) VALUES('PLATFORM');
CREATE TABLE command_request (
 scope_id VARCHAR(64) NOT NULL, idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_user_id VARCHAR(64) NOT NULL, command_type VARCHAR(64) NOT NULL, request_hash CHAR(64) NOT NULL,
 result_snapshot JSON NOT NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(scope_id,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
