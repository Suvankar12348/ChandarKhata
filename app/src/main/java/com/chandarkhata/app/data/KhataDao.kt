package com.chandarkhata.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class KhataDao {
    @Query("SELECT * FROM events ORDER BY year DESC, id DESC")
    abstract fun events(): Flow<List<PujaEvent>>

    @Query("SELECT * FROM events WHERE isCurrent = 1 LIMIT 1")
    abstract fun currentEvent(): Flow<PujaEvent?>

    @Insert
    abstract suspend fun insertEvent(event: PujaEvent): Long

    @Query("UPDATE events SET isCurrent = CASE WHEN id = :id THEN 1 ELSE 0 END")
    abstract suspend fun chooseEvent(id: Long)

    @Query("SELECT * FROM donors ORDER BY name COLLATE NOCASE")
    abstract fun donors(): Flow<List<Donor>>

    @Query("SELECT * FROM donors WHERE id = :id LIMIT 1")
    abstract suspend fun donorById(id: Long): Donor?

    @Query("SELECT * FROM donors WHERE lower(trim(contactNumber)) = lower(trim(:phone)) LIMIT 1")
    abstract suspend fun donorByPhone(phone: String): Donor?

    @Insert
    abstract suspend fun insertDonor(donor: Donor): Long

    @Query("SELECT * FROM bills WHERE eventId = :eventId ORDER BY createdAt DESC")
    abstract fun bills(eventId: Long): Flow<List<DonationBill>>

    @Query("""
        SELECT b.*, d.name AS donorName, d.address AS donorAddress, d.contactNumber AS donorPhone,
        COALESCE((SELECT SUM(p.amountPaise) FROM payments p WHERE p.billId = b.id), 0) AS receivedPaise
        FROM bills b JOIN donors d ON d.id = b.donorId
        WHERE b.eventId = :eventId
        ORDER BY b.createdAt DESC
    """)
    abstract fun billRows(eventId: Long): Flow<List<BillRow>>

    @Query("""
        SELECT b.*, d.name AS donorName, d.address AS donorAddress, d.contactNumber AS donorPhone,
        COALESCE((SELECT SUM(p.amountPaise) FROM payments p WHERE p.billId = b.id), 0) AS receivedPaise
        FROM bills b JOIN donors d ON d.id = b.donorId
        WHERE b.id = :billId LIMIT 1
    """)
    abstract suspend fun billRow(billId: Long): BillRow?

    @Query("SELECT * FROM bills WHERE id = :id LIMIT 1")
    abstract suspend fun billById(id: Long): DonationBill?

    @Insert
    abstract suspend fun insertBill(bill: DonationBill): Long

    @Query("SELECT COUNT(*) FROM bills WHERE eventId = :eventId")
    abstract suspend fun billCount(eventId: Long): Int

    @Insert
    abstract suspend fun insertPayment(payment: DonationPayment): Long

    @Query("SELECT * FROM payments WHERE billId = :billId ORDER BY paidAt DESC")
    abstract fun paymentsForBill(billId: Long): Flow<List<DonationPayment>>

    @Query("SELECT * FROM payments ORDER BY paidAt DESC")
    abstract fun allPayments(): Flow<List<DonationPayment>>

    @Transaction
    open suspend fun collectPayment(payment: DonationPayment) {
        require(payment.amountPaise > 0) { "Payment must be greater than zero." }
        require(payment.cashPaise >= 0 && payment.upiPaise >= 0) { "Payment split cannot be negative." }
        require(payment.cashPaise + payment.upiPaise == payment.amountPaise) { "Cash and UPI split must equal total." }
        val bill = billRow(payment.billId) ?: error("Bill not found.")
        require(payment.amountPaise <= bill.duePaise) { "Payment cannot exceed outstanding due." }
        insertPayment(payment)
    }

    @Query("SELECT * FROM expenses WHERE eventId = :eventId ORDER BY recordedAt DESC")
    abstract fun expenses(eventId: Long): Flow<List<Expense>>

    @Insert
    abstract suspend fun insertExpense(expense: Expense): Long

    @Query("SELECT * FROM events")
    abstract suspend fun allEventsOnce(): List<PujaEvent>

    @Query("SELECT * FROM donors")
    abstract suspend fun allDonorsOnce(): List<Donor>

    @Query("SELECT * FROM bills")
    abstract suspend fun allBillsOnce(): List<DonationBill>

    @Query("SELECT * FROM payments")
    abstract suspend fun allPaymentsOnce(): List<DonationPayment>

    @Query("SELECT * FROM expenses")
    abstract suspend fun allExpensesOnce(): List<Expense>

    @Transaction
    open suspend fun restore(payload: BackupPayload) {
        // Restore is intentionally replace-all, atomic, and preserves primary/foreign keys.
        require(payload.formatVersion == 1) { "Unsupported backup version." }
        require(payload.events.map { it.id }.distinct().size == payload.events.size)
        require(payload.donors.map { it.id }.distinct().size == payload.donors.size)
        require(payload.bills.map { it.id }.distinct().size == payload.bills.size)
        require(payload.payments.map { it.id }.distinct().size == payload.payments.size)
        require(payload.expenses.map { it.id }.distinct().size == payload.expenses.size)
        require(payload.bills.all { b -> payload.donors.any { it.id == b.donorId } && payload.events.any { it.id == b.eventId } })
        require(payload.payments.all { p -> payload.bills.any { it.id == p.billId } })
        require(payload.expenses.all { e -> payload.events.any { it.id == e.eventId } })

        clearPayments()
        clearExpenses()
        clearBills()
        clearDonors()
        clearEvents()
        payload.events.forEach { insertEvent(it) }
        payload.donors.forEach { insertDonor(it) }
        payload.bills.forEach { insertBill(it) }
        payload.payments.forEach { insertPayment(it) }
        payload.expenses.forEach { insertExpense(it) }
    }

    @Query("DELETE FROM payments") abstract suspend fun clearPayments()
    @Query("DELETE FROM expenses") abstract suspend fun clearExpenses()
    @Query("DELETE FROM bills") abstract suspend fun clearBills()
    @Query("DELETE FROM donors") abstract suspend fun clearDonors()
    @Query("DELETE FROM events") abstract suspend fun clearEvents()
}
