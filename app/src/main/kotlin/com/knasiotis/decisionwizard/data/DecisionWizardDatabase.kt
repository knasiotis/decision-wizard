package com.knasiotis.decisionwizard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [GraphEntity::class, SessionEntity::class],
    version = 2,
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
                // TEMPORARY, and it must not survive first real use.
                //
                // Nobody depends on this database yet, so a schema change wipes
                // it instead of carrying migration code that exists only to
                // preserve throwaway test data. The moment anyone keeps graphs
                // they care about, delete this line and write real migrations —
                // graphs are hand-authored and cannot be re-downloaded.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
