INSERT INTO campus(id,name) VALUES ('CAMPUS-A','演示园区');
INSERT INTO building(id,campus_id,name) VALUES
('BLD-1','CAMPUS-A','1号楼'),('BLD-2','CAMPUS-A','2号楼'),('BLD-3','CAMPUS-A','3号楼');
INSERT INTO floor(id,building_id,name) VALUES
('FL-1-1','BLD-1','1层'),('FL-1-2','BLD-1','2层'),
('FL-2-1','BLD-2','1层'),('FL-2-2','BLD-2','2层'),
('FL-3-1','BLD-3','1层'),('FL-3-2','BLD-3','2层');
INSERT INTO area(id,floor_id,name) VALUES
('AREA-1-1','FL-1-1','东侧走廊'),('AREA-1-2','FL-1-2','设备间'),
('AREA-2-1','FL-2-1','大厅'),('AREA-2-2','FL-2-2','设备间'),
('AREA-3-1','FL-3-1','实验区'),('AREA-3-2','FL-3-2','设备间');
