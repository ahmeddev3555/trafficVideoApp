package com.trafficwatch.app.core.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v3 -> v4: persist the continuous GPS / rotation sample series on the report row so a
 * retried upload (which rebuilds the request from this row) can resend them. Additive,
 * nullable, no default - existing rows get NULL. See
 * docs/superpowers/specs/2026-09-03-upload-metadata-fidelity-design.md.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reports ADD COLUMN locationSamplesJson TEXT")
        db.execSQL("ALTER TABLE reports ADD COLUMN rotationSamplesJson TEXT")
    }
}
