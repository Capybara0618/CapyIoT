CREATE TABLE shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by VARCHAR(255) NOT NULL
) ENGINE=InnoDB;
CREATE INDEX ix_snapshot_rebuild ON device_state_snapshot(connectivity_status,device_id);
