ALTER TABLE osm_lookup_cache ADD COLUMN unknown_reason VARCHAR(32);
ALTER TABLE osm_lookup_cache ADD CONSTRAINT chk_osm_lookup_cache_unknown_reason
    CHECK (unknown_reason IN (
        'NO_ONEWAY_TAG', 'AMBIGUOUS_NEAREST_STREET', 'DIVIDED_CARRIAGEWAY', 'NOT_CROSS_CHECKED'
    ));

-- Every bucket re-resolves through the new multi-mirror path on next use; this clears the
-- known-bad OneWay(11.23) rows for the خیبان جناح bucket (reports 759cd / 24908 / a5275)
-- immediately rather than waiting out the 30-day TTL.
TRUNCATE TABLE osm_lookup_cache;
