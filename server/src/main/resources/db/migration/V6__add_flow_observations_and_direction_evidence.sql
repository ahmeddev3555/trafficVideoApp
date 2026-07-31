CREATE TABLE flow_observations (
    id UUID PRIMARY KEY,
    lat_bucket NUMERIC(8,4) NOT NULL,
    lon_bucket NUMERIC(8,4) NOT NULL,
    bearing_degrees NUMERIC(6,2) NOT NULL,
    vehicle_count INT NOT NULL,
    resultant_length NUMERIC(4,3) NOT NULL,
    reporter_id UUID NOT NULL,
    report_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_flow_observations_bucket ON flow_observations (lat_bucket, lon_bucket);

ALTER TABLE reports ADD COLUMN direction_evidence JSONB;
