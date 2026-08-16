-- Adds versions.class_count / method_count / field_count and fills them from the existing rows.
--
-- GET /api/v1/versions reports a class, method and field count for every version. It used to derive
-- them with three grouped COUNTs over the whole index (~51M rows on a full index), which cost 1.6s
-- warm and 6.5s cold on the first request after each server start. The indexer now records the three
-- numbers on the version row, and the server reads the columns.
--
-- Run this against an existing index instead of re-indexing. Stop the server first: it opens the
-- database read-only and fails with `no such column: versions.class_count` until the columns exist.
-- An index built after this change fills the columns on its own.
--
-- The three UPDATEs scan the classes, methods and fields tables once each. Expect a few seconds on a
-- warm page cache, a minute or so on a cold one.
ALTER TABLE versions ADD COLUMN class_count BIGINT;
ALTER TABLE versions ADD COLUMN method_count BIGINT;
ALTER TABLE versions ADD COLUMN field_count BIGINT;

UPDATE versions SET class_count = (SELECT COUNT(*) FROM classes WHERE classes.version_id = versions.id);
UPDATE versions SET method_count = (SELECT COUNT(*) FROM methods WHERE methods.version_id = versions.id);
UPDATE versions SET field_count = (SELECT COUNT(*) FROM fields WHERE fields.version_id = versions.id);
