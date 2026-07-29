ALTER TABLE reports ADD COLUMN street_name VARCHAR(255);

CREATE TABLE osm_lookup_cache (
    id UUID PRIMARY KEY,
    lat_bucket NUMERIC(8,4) NOT NULL,
    lon_bucket NUMERIC(8,4) NOT NULL,
    street_name VARCHAR(255),
    direction_state VARCHAR(20) NOT NULL,
    legal_bearing_degrees NUMERIC(6,2),
    osm_way_id BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_osm_lookup_cache_bucket UNIQUE (lat_bucket, lon_bucket),
    CONSTRAINT chk_osm_lookup_cache_direction_state CHECK (direction_state IN ('NOT_FOUND','UNKNOWN','TWO_WAY','ONE_WAY'))
);
