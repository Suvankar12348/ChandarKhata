package com.chandarkhata.app.data

import androidx.room.*

@Entity(tableName = "events", indices = [Index(value = ["name", "year"], unique = true)])
data class PujaEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val year: Int,
    val isCurrent: Boolean = false
)

@Entity(tableName = "donors", indices = [Index("contactNumber")])
data class Donor(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val address: String,
    val contactNumber: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "bills",
    foreignKeys = [
        ForeignKey(entity = Donor::class, parentColumns = ["id"], childColumns = ["donorId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = PujaEvent::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index("donorId"), Index("eventId"), Index(value = ["billNumber"], unique = true)]
)
data class DonationBill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billNumber: String,
    val donorId: Long,
    val eventId: Long,
    val amountPaise: Long,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "payments",
    foreignKeys = [ForeignKey(entity = DonationBill::class, parentColumns = ["id"], childColumns = ["billId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("billId")]
)
data class DonationPayment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billId: Long,
    val amountPaise: Long,
    val method: String, // CASH, UPI_TEST, MIXED
    val cashPaise: Long,
    val upiPaise: Long,
    val collector: String,
    val transactionRef: String = "",
    val isUpiVerified: Boolean = false,
    val paidAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "expenses",
    foreignKeys = [ForeignKey(entity = PujaEvent::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("eventId")]
)
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val category: String,
    val description: String,
    val amountPaise: Long,
    val method: String = "CASH",
    val recordedAt: Long = System.currentTimeMillis()
)

data class BillRow(
    @Embedded val bill: DonationBill,
    @ColumnInfo(name = "donorName") val donorName: String,
    @ColumnInfo(name = "donorAddress") val donorAddress: String,
    @ColumnInfo(name = "donorPhone") val donorPhone: String,
    @ColumnInfo(name = "receivedPaise") val receivedPaise: Long
) {
    val duePaise: Long get() = (bill.amountPaise - receivedPaise).coerceAtLeast(0)
    val status: String get() = when {
        receivedPaise <= 0 -> "UNPAID"
        duePaise == 0L -> "PAID"
        else -> "PARTIAL"
    }
}
