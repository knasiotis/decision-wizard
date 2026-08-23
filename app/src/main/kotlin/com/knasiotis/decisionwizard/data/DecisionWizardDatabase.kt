package com.knasiotis.decisionwizard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [GraphEntity::class, SessionEntity::class],
    version = 5,
    exportSchema = true
)
abstract class DecisionWizardDatabase : RoomDatabase() {

    abstract fun graphs(): GraphDao
    abstract fun sessions(): SessionDao

    companion object {
        private const val NAME = "decision-wizard.db"

        @Volatile
        private var instance: DecisionWizardDatabase? = null

        fun get(context: Context): DecisionWizardDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): DecisionWizardDatabase =
            Room.databaseBuilder(context, DecisionWizardDatabase::class.java, NAME)
                // No destructive fallback. Graphs are hand-authored and cannot
                // be re-downloaded, so a missing migration must fail loudly
                // rather than silently wipe them. See Migrations.kt.
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
