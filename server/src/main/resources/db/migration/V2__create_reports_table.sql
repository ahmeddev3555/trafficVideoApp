CREATE TABLE reports (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    video_path VARCHAR(255) NOT NULL,
    latitude NUMERIC NOT NULL,
    longitude NUMERIC NOT NULL,
    accuracy NUMERIC NOT NULL,
    altitude NUMERIC NOT NULL,
    bearing NUMERIC NOT NULL,
    speed NUMERIC NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    duration_ms BIGINT NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    license_plate VARCHAR(255),
    confidence NUMERIC,
    analysis_message VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_reports_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_reports_status CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED'))
);

CREATE INDEX idx_reports_user_id ON reports (user_id);
CREATE INDEX idx_reports_status ON reports (status);
CREATE INDEX idx_reports_user_id_status ON reports (user_id, status);
