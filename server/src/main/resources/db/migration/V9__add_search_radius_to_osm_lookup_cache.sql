ALTER TABLE osm_lookup_cache ADD COLUMN search_radius_meters NUMERIC(6,2) NOT NULL DEFAULT 50.0;
ALTER TABLE osm_lookup_cache ALTER COLUMN search_radius_meters DROP DEFAULT;
