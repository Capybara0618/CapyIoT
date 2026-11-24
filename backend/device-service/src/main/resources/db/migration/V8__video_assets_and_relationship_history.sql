-- Additive transition: legacy physical id/campus_id/device_code columns remain stable.
-- The Core adapter exposes them as deviceId/parkId/assetCode. No historical table is dropped.
CREATE TABLE park (
 park_id VARCHAR(64) PRIMARY KEY, park_code VARCHAR(64) NOT NULL UNIQUE, name VARCHAR(100) NOT NULL,
 description VARCHAR(1000), status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO park(park_id,park_code,name) SELECT id,id,name FROM campus;

ALTER TABLE area MODIFY floor_id VARCHAR(64) NULL,
 ADD park_id VARCHAR(64), ADD parent_area_id VARCHAR(64), ADD area_type VARCHAR(16) NOT NULL DEFAULT 'AREA',
 ADD area_code VARCHAR(64), ADD status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', ADD version BIGINT NOT NULL DEFAULT 0;
UPDATE area a JOIN floor f ON f.id=a.floor_id JOIN building b ON b.id=f.building_id
 SET a.park_id=b.campus_id,a.parent_area_id=f.id,a.area_code=a.id;
INSERT INTO area(id,name,park_id,area_type,area_code) SELECT id,name,campus_id,'BUILDING',id FROM building;
INSERT INTO area(id,name,park_id,parent_area_id,area_type,area_code)
 SELECT f.id,f.name,b.campus_id,f.building_id,'FLOOR',f.id FROM floor f JOIN building b ON b.id=f.building_id;
ALTER TABLE area MODIFY park_id VARCHAR(64) NOT NULL, MODIFY area_code VARCHAR(64) NOT NULL,
 ADD UNIQUE KEY uk_area_park_code(park_id,area_code), ADD UNIQUE KEY uk_area_park_id(park_id,id);
ALTER TABLE area
 ADD FOREIGN KEY(park_id) REFERENCES park(park_id), ADD FOREIGN KEY(park_id,parent_area_id) REFERENCES area(park_id,id),
 ADD CHECK(area_type IN ('BUILDING','FLOOR','AREA')), ADD CHECK(parent_area_id IS NULL OR parent_area_id<>id);
CREATE TABLE legacy_location_mapping (
 legacy_table VARCHAR(16) NOT NULL, legacy_id VARCHAR(64) NOT NULL, area_id VARCHAR(64) NOT NULL,
 migrated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY(legacy_table,legacy_id), FOREIGN KEY(area_id) REFERENCES area(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO legacy_location_mapping(legacy_table,legacy_id,area_id) SELECT 'building',id,id FROM building;
INSERT INTO legacy_location_mapping(legacy_table,legacy_id,area_id) SELECT 'floor',id,id FROM floor;
INSERT INTO legacy_location_mapping(legacy_table,legacy_id,area_id) SELECT 'area',id,id FROM area WHERE area_type='AREA';

ALTER TABLE device DROP CHECK ck_device_type,
 MODIFY building_id VARCHAR(64) NULL, MODIFY floor_id VARCHAR(64) NULL,
 ADD device_type VARCHAR(16), ADD asset_status VARCHAR(32) NOT NULL DEFAULT 'REGISTERED',
 ADD manufacturer VARCHAR(100), ADD model VARCHAR(100), ADD serial_number VARCHAR(128), ADD management_ip VARCHAR(64),
 ADD installed_at DATETIME(3), ADD device_secret_hash VARCHAR(255), ADD credential_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
 ADD credential_rotated_at DATETIME(3), ADD criticality VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
 ADD capabilities_json JSON, ADD managed_source VARCHAR(16) NOT NULL DEFAULT 'SIMULATOR',
 ADD legacy_only BOOLEAN NOT NULL DEFAULT FALSE,
 ADD CHECK(type IN ('CAMERA','IPC','NVR','SWITCH','SENSOR','ACCESS_CONTROL')),
 ADD CHECK(device_type IS NULL OR device_type IN ('IPC','NVR','SWITCH')),
 ADD CHECK(asset_status IN ('REGISTERED','IN_SERVICE','OUT_OF_SERVICE','RETIRED'));
UPDATE device SET device_type=CASE type WHEN 'CAMERA' THEN 'IPC' WHEN 'SWITCH' THEN 'SWITCH' ELSE NULL END,
 legacy_only=type IN ('SENSOR','ACCESS_CONTROL'),device_secret_hash=password_hash,
 area_id=COALESCE(area_id,floor_id,building_id),asset_status=CASE WHEN enabled THEN 'IN_SERVICE' ELSE 'OUT_OF_SERVICE' END;
ALTER TABLE device ADD FOREIGN KEY(campus_id,area_id) REFERENCES area(park_id,id);

CREATE TABLE monitoring_point (
 monitoring_point_id VARCHAR(64) PRIMARY KEY, park_id VARCHAR(64) NOT NULL, area_id VARCHAR(64) NOT NULL,
 point_code VARCHAR(64) NOT NULL, name VARCHAR(100) NOT NULL, description VARCHAR(1000), importance VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_point_code(park_id,point_code), FOREIGN KEY(park_id,area_id) REFERENCES area(park_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE monitoring_point_binding (
 binding_id VARCHAR(64) PRIMARY KEY, monitoring_point_id VARCHAR(64) NOT NULL, ipc_device_id VARCHAR(64) NOT NULL,
 valid_from DATETIME(3) NOT NULL, valid_to DATETIME(3), reason VARCHAR(1000) NOT NULL, created_by VARCHAR(64) NOT NULL,
 ended_by VARCHAR(64), version BIGINT NOT NULL DEFAULT 0,
 active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN valid_to IS NULL THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY uk_point_current(monitoring_point_id,active_marker), UNIQUE KEY uk_ipc_point_current(ipc_device_id,active_marker),
 FOREIGN KEY(monitoring_point_id) REFERENCES monitoring_point(monitoring_point_id), FOREIGN KEY(ipc_device_id) REFERENCES device(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE network_dependency (
 dependency_id VARCHAR(64) PRIMARY KEY, park_id VARCHAR(64) NOT NULL, upstream_switch_id VARCHAR(64) NOT NULL,
 downstream_device_id VARCHAR(64) NOT NULL, upstream_port_ref VARCHAR(64), downstream_port_ref VARCHAR(64),
 valid_from DATETIME(3) NOT NULL, valid_to DATETIME(3), change_reason VARCHAR(1000) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN valid_to IS NULL THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY uk_network_current_parent(downstream_device_id,active_marker),
 UNIQUE KEY uk_network_current_port(upstream_switch_id,upstream_port_ref,active_marker),
 FOREIGN KEY(upstream_switch_id) REFERENCES device(id), FOREIGN KEY(downstream_device_id) REFERENCES device(id), CHECK(upstream_switch_id<>downstream_device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO network_dependency(dependency_id,park_id,upstream_switch_id,downstream_device_id,valid_from,change_reason)
 SELECT l.id,l.campus_id,l.upstream_device_id,l.downstream_device_id,CURRENT_TIMESTAMP(3),'Legacy network_link migration'
 FROM network_link l JOIN device u ON u.id=l.upstream_device_id JOIN device d ON d.id=l.downstream_device_id
 WHERE u.device_type='SWITCH' AND NOT u.legacy_only AND NOT d.legacy_only;
CREATE TABLE power_dependency (
 power_dependency_id VARCHAR(64) PRIMARY KEY, park_id VARCHAR(64) NOT NULL, poe_switch_id VARCHAR(64) NOT NULL,
 ipc_device_id VARCHAR(64) NOT NULL, poe_port VARCHAR(64) NOT NULL, valid_from DATETIME(3) NOT NULL, valid_to DATETIME(3),
 change_reason VARCHAR(1000) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN valid_to IS NULL THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY uk_power_current_ipc(ipc_device_id,active_marker), UNIQUE KEY uk_power_current_port(poe_switch_id,poe_port,active_marker),
 FOREIGN KEY(poe_switch_id) REFERENCES device(id), FOREIGN KEY(ipc_device_id) REFERENCES device(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE video_channel_association (
 association_id VARCHAR(64) PRIMARY KEY, park_id VARCHAR(64) NOT NULL, ipc_device_id VARCHAR(64) NOT NULL,
 nvr_device_id VARCHAR(64) NOT NULL, channel_no INT NOT NULL, valid_from DATETIME(3) NOT NULL, valid_to DATETIME(3),
 change_reason VARCHAR(1000) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN valid_to IS NULL THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY uk_video_current_ipc(ipc_device_id,active_marker), UNIQUE KEY uk_video_current_channel(nvr_device_id,channel_no,active_marker),
 FOREIGN KEY(nvr_device_id) REFERENCES device(id), FOREIGN KEY(ipc_device_id) REFERENCES device(id), CHECK(channel_no>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE command_lock (scope_id VARCHAR(64) PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO command_lock(scope_id) SELECT park_id FROM park;
CREATE TABLE command_request (
 scope_id VARCHAR(64) NOT NULL, idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_user_id VARCHAR(64) NOT NULL, command_type VARCHAR(64) NOT NULL, request_hash CHAR(64) NOT NULL,
 result_snapshot JSON NOT NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY(scope_id,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
