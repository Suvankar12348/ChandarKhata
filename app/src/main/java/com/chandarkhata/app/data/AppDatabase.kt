package com.chandarkhata.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PujaEvent::class, Donor::class, DonationBill::class, DonationPayment::class, Expense::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): KhataDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "chandar_khata.db"
            ).build().also { INSTANCE = it }
        }
    }
}

data class BackupPayload(
    val formatVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val events: List<PujaEvent>,
    val donors: List<Donor>,
    val bills: List<DonationBill>,
    val payments: List<DonationPayment>,
    val expenses: List<Expense>
)
