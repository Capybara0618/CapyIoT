-- Target identity is (source,eventId). Legacy rows retain their source and original IDs.
ALTER TABLE device_event DROP INDEX event_id,ADD UNIQUE KEY uk_event_source_identity(source,event_id);
