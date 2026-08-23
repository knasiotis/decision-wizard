package com.knasiotis.decisionwizard.data

import androidx.room.migration.Migration

/**
 * Every schema change from version 5 onwards needs a migration here. There is no
 * destructive fallback any more: a missing migration fails loudly on launch
 * rather than quietly deleting hand-authored graphs that cannot be recovered.
 *
 * Two things count as a schema change, and the second is the one that catches
 * people out:
 *
 *  1. A column, table or index — Room's own schema, diffable against the JSON in
 *     `app/schemas/`.
 *  2. **The shape of anything stored inside a blob column** — `graphs.body` and
 *     `sessions.stateJson` are JSON, and Room cannot see inside them. It will
 *     keep rows this build cannot parse. A build already shipped that crashed on
 *     launch for exactly this reason.
 *
 * For (2), prefer a change old data still parses under — a new field with a
 * default — over one that needs rewriting. When it must be rewritten, bump the
 * version and write a migration that rewrites or drops the affected rows.
 */
internal val MIGRATIONS: Array<Migration> = arrayOf(
    // Version 5 is the baseline. Add migrations here as the schema moves on.
)
