package com.chandarkhata.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.chandarkhata.app.data.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Purple = Color(0xFF4A148C)
private val Lavender = Color(0xFFF3E5F5)
private val DueRed = Color(0xFFC62828)
private val PaidGreen = Color(0xFF2E7D32)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.get(this).dao()
        setContent {
            val vm: KhataViewModel = viewModel(factory = KhataViewModel.Factory(dao))
            MaterialTheme(colorScheme = lightColorScheme(primary = Purple, secondary = Color(0xFFFFD600))) {
                KhataApp(vm)
            }
        }
    }
}

class KhataViewModel(private val dao: KhataDao) : ViewModel() {
    val events = dao.events().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val donors = dao.donors().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val currentEvent = dao.currentEvent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val bills = currentEvent.flatMapLatest { e -> if (e == null) flowOf(emptyList()) else dao.billRows(e.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val expenses = currentEvent.flatMapLatest { e -> if (e == null) flowOf(emptyList()) else dao.expenses(e.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val payments = dao.allPayments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var message by mutableStateOf<String?>(null)
        private set

    fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); message = "Saved successfully" }
            catch (e: Exception) { message = e.message ?: "Something went wrong" }
        }
    }

    fun addEvent(name: String, year: Int) = run {
        require(name.isNotBlank() && year in 2000..2100) { "Enter a valid event name and year." }
        val id = dao.insertEvent(PujaEvent(name = name.trim(), year = year, isCurrent = events.value.isEmpty()))
        if (events.value.isEmpty()) dao.chooseEvent(id)
    }

    fun selectEvent(id: Long) = run { dao.chooseEvent(id) }

    fun createBill(name: String, address: String, phone: String, amount: String) = run {
        val event = currentEvent.value ?: error("Create or select an event first.")
        require(name.isNotBlank() && address.isNotBlank() && phone.isNotBlank()) { "Complete all four fields." }
        val paise = moneyToPaise(amount)
        require(paise > 0) { "Donation amount must be greater than zero." }
        val donor = dao.donorByPhone(phone.trim()) ?: Donor(
            name = name.trim(), address = address.trim(), contactNumber = phone.trim()
        ).let { dao.insertDonor(it) }.let { dao.donorById(it)!! }
        val sequence = dao.billCount(event.id) + 1
        val billNo = "CK-${event.year}-${sequence.toString().padStart(5, '0')}"
        dao.insertBill(DonationBill(
            billNumber = billNo, donorId = donor.id, eventId = event.id, amountPaise = paise
        ))
    }

    fun pay(billId: Long, amount: String, method: String, cash: String, upi: String, collector: String, ref: String) = run {
        val total = moneyToPaise(amount)
        val cashValue = if (method == "UPI_TEST") 0 else if (method == "CASH") total else moneyToPaise(cash)
        val upiValue = if (method == "CASH") 0 else if (method == "UPI_TEST") total else moneyToPaise(upi)
        dao.collectPayment(DonationPayment(
            billId = billId, amountPaise = total, method = method,
            cashPaise = cashValue, upiPaise = upiValue,
            collector = collector.ifBlank { "Not specified" }, transactionRef = ref,
            isUpiVerified = false
        ))
    }

    fun addExpense(category: String, description: String, amount: String, method: String) = run {
        val event = currentEvent.value ?: error("Create or select an event first.")
        require(category.isNotBlank() && description.isNotBlank()) { "Enter category and description." }
        val value = moneyToPaise(amount)
        require(value > 0) { "Amount must be greater than zero." }
        dao.insertExpense(Expense(eventId = event.id, category = category, description = description, amountPaise = value, method = method))
    }

    suspend fun exportJson(context: Context, uri: android.net.Uri) {
        val payload = BackupPayload(
            events = dao.allEventsOnce(), donors = dao.allDonorsOnce(), bills = dao.allBillsOnce(),
            payments = dao.allPaymentsOnce(), expenses = dao.allExpensesOnce()
        )
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(Gson().toJson(payload)) }
            ?: error("Could not write backup file.")
    }

    suspend fun importJson(context: Context, uri: android.net.Uri) {
        val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Could not read backup file.")
        val payload = Gson().fromJson(json, BackupPayload::class.java) ?: error("Invalid backup.")
        dao.restore(payload)
    }

    fun clearMessage() { message = null }

    class Factory(private val dao: KhataDao) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = KhataViewModel(dao) as T
    }
}

private fun moneyToPaise(input: String): Long {
    val value = input.trim().toBigDecimalOrNull() ?: error("Enter a valid amount.")
    require(value >= java.math.BigDecimal.ZERO) { "Amount cannot be negative." }
    return value.multiply(java.math.BigDecimal(100)).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
}
private fun money(paise: Long) = "₹" + String.format(Locale("en", "IN"), "%,.2f", paise / 100.0)
private fun date(ts: Long) = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ts))

@Composable
fun KhataApp(vm: KhataViewModel) {
    val event by vm.currentEvent.collectAsState()
    val events by vm.events.collectAsState()
    val bills by vm.bills.collectAsState()
    val donors by vm.donors.collectAsState()
    val expenses by vm.expenses.collectAsState()
    val payments by vm.payments.collectAsState()
    val context = LocalContext.current
    var tab by remember { mutableStateOf("Home") }
    var selectedBill by remember { mutableStateOf<Long?>(null) }
    var showNewEvent by remember { mutableStateOf(false) }
    var showNewEntry by remember { mutableStateOf(false) }
    var showExpense by remember { mutableStateOf(false) }
    var showPayment by remember { mutableStateOf<Long?>(null) }
    var detailBill by remember { mutableStateOf<Long?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.run { vm.exportJson(context, uri) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.run { vm.importJson(context, uri) }
    }

    Scaffold(
        topBar = {
            TopAppBarKhata(title = "চাঁদার খাতা", subtitle = event?.let { "${it.name} • ${it.year}" } ?: "Puja & Event Donation")
        },
        bottomBar = {
            NavigationBar {
                listOf("Home", "Entry", "Bills", "Donors", "More").forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item; detailBill = null },
                        icon = {
                            Icon(
                                when (item) {
                                    "Home" -> Icons.Default.Home
                                    "Entry" -> Icons.Default.AddCircle
                                    "Bills" -> Icons.Default.ReceiptLong
                                    "Donors" -> Icons.Default.People
                                    else -> Icons.Default.Menu
                                }, contentDescription = item
                            )
                        },
                        label = { Text(item) }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (detailBill != null) {
                val row = bills.firstOrNull { it.bill.id == detailBill }
                if (row != null) BillDetail(row, vm, onPay = { showPayment = row.bill.id }, onBack = { detailBill = null })
                else Text("Bill not found in selected event.")
            } else when (tab) {
                "Home" -> Dashboard(event, bills, expenses, onNew = { showNewEntry = true }, onDues = { tab = "Bills" })
                "Entry" -> NewEntryForm(onSave = { n, a, p, amt -> vm.createBill(n, a, p, amt); showNewEntry = false })
                "Bills" -> BillList(bills, onClick = { detailBill = it }, onPay = { showPayment = it })
                "Donors" -> DonorList(donors, bills)
                else -> MoreScreen(
                    events = events, bills = bills, payments = payments, expenses = expenses,
                    onAddEvent = { showNewEvent = true }, onChooseEvent = { vm.selectEvent(it) },
                    onExpense = { showExpense = true },
                    onExport = { exportLauncher.launch("ChandarKhata_Backup.json") },
                    onImport = { importLauncher.launch(arrayOf("application/json", "*/*")) }
                )
            }
        }
    }

    if (showNewEvent) SimpleDialog("New event", listOf("Event name", "Year"), onDismiss = { showNewEvent = false }) { values ->
        vm.addEvent(values[0], values[1].toIntOrNull() ?: 0); showNewEvent = false
    }
    if (showNewEntry) NewEntryDialog(onDismiss = { showNewEntry = false }) { n, a, p, amt ->
        vm.createBill(n, a, p, amt); showNewEntry = false
    }
    if (showExpense) ExpenseDialog(onDismiss = { showExpense = false }) { c, d, a, m ->
        vm.addExpense(c, d, a, m); showExpense = false
    }
    if (showPayment != null) {
        val row = bills.firstOrNull { it.bill.id == showPayment }
        if (row != null) PaymentDialog(row, onDismiss = { showPayment = null }) { amt, method, cash, upi, collector, ref ->
            vm.pay(row.bill.id, amt, method, cash, upi, collector, ref); showPayment = null
        }
    }

    val msg = vm.message
    if (msg != null) AlertDialog(
        onDismissRequest = vm::clearMessage,
        title = { Text("Chandar Khata") }, text = { Text(msg) },
        confirmButton = { TextButton(onClick = vm::clearMessage) { Text("OK") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopAppBarKhata(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Purple, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
private fun Dashboard(event: PujaEvent?, bills: List<BillRow>, expenses: List<Expense>, onNew: () -> Unit, onDues: () -> Unit) {
    val received = bills.sumOf { it.receivedPaise }
    val due = bills.sumOf { it.duePaise }
    val total = bills.sumOf { it.bill.amountPaise }
    val spent = expenses.sumOf { it.amountPaise }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (event == null) {
            Text("Start by creating your first Puja or event.", style = MaterialTheme.typography.titleMedium)
        } else {
            Text("Overview • ${event.name} ${event.year}", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Total billed", money(total), Modifier.weight(1f))
                StatCard("Collected", money(received), Modifier.weight(1f), PaidGreen)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Outstanding", money(due), Modifier.weight(1f), DueRed)
                StatCard("Expenses", money(spent), Modifier.weight(1f))
            }
            Text("Net cash position (recorded collections less expenses): ${money(received - spent)}")
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("＋ New chanda entry") }
            OutlinedButton(onClick = onDues, modifier = Modifier.fillMaxWidth()) { Text("View bills & pending dues (${bills.count { it.duePaise > 0 }})") }
            Text("Recent bills", fontWeight = FontWeight.Bold)
            bills.take(5).forEach { BillLine(it, onClick = {}) }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier, tint: Color = Purple) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Lavender)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
            Text(value, color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun NewEntryForm(onSave: (String, String, String, String) -> Unit) {
    Text("New chanda entry", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    NewEntryFields(onSave)
}

@Composable
private fun NewEntryDialog(onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var n by remember { mutableStateOf("") }; var a by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }; var amt by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("নতুন চাঁদা / New Entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(n, { n = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(a, { a = it }, label = { Text("Address") })
                OutlinedTextField(p, { p = it }, label = { Text("Contact / WhatsApp") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
                OutlinedTextField(amt, { amt = it }, label = { Text("Donation Amount (₹)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onSave(n, a, p, amt) }) { Text("Save & Create Bill") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NewEntryFields(onSave: (String, String, String, String) -> Unit) {
    var n by remember { mutableStateOf("") }; var a by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }; var amt by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(n, { n = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(a, { a = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(p, { p = it }, label = { Text("Contact / WhatsApp Number") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        OutlinedTextField(amt, { amt = it }, label = { Text("Donation Amount (₹)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Button(onClick = { onSave(n, a, p, amt) }, modifier = Modifier.fillMaxWidth()) { Text("SAVE & CREATE BILL") }
        Text("Payments are recorded separately from this form.", color = Color.Gray)
    }
}

@Composable
private fun BillList(rows: List<BillRow>, onClick: (Long) -> Unit, onPay: (Long) -> Unit) {
    Text("Bills & outstanding dues", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rows, key = { it.bill.id }) { row ->
            Card(Modifier.fillMaxWidth().clickable { onClick(row.bill.id) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(row.donorName, fontWeight = FontWeight.Bold)
                    Text("${row.bill.billNumber} • ${date(row.bill.createdAt)}")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Bill ${money(row.bill.amountPaise)}")
                        Text("Due ${money(row.duePaise)}", color = if (row.duePaise > 0) DueRed else PaidGreen)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.status, color = if (row.duePaise > 0) DueRed else PaidGreen)
                        if (row.duePaise > 0) TextButton(onClick = { onPay(row.bill.id) }) { Text("Collect payment") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BillLine(row: BillRow, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(row.donorName, fontWeight = FontWeight.SemiBold)
                Text(row.bill.billNumber, style = MaterialTheme.typography.bodySmall)
            }
            Text(money(row.duePaise), color = if (row.duePaise > 0) DueRed else PaidGreen)
        }
    }
}

@Composable
private fun BillDetail(row: BillRow, vm: KhataViewModel, onPay: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("← Back to bills") }
        Text(row.bill.billNumber, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(row.donorName); Text(row.donorAddress); Text(row.donorPhone)
        StatCard("Bill amount", money(row.bill.amountPaise), Modifier.fillMaxWidth())
        StatCard("Received", money(row.receivedPaise), Modifier.fillMaxWidth(), PaidGreen)
        StatCard("Balance due", money(row.duePaise), Modifier.fillMaxWidth(), DueRed)
        Text("Status: ${row.status}")
        if (row.duePaise > 0) Button(onClick = onPay, modifier = Modifier.fillMaxWidth()) { Text("Collect payment") }
        Text("Payment history", fontWeight = FontWeight.Bold)
        val pays by vm.payments.collectAsState()
        pays.filter { it.billId == row.bill.id }.forEach {
            Text("${money(it.amountPaise)} • ${it.method} • ${date(it.paidAt)} • ${it.collector}")
        }
    }
}

@Composable
private fun DonorList(donors: List<Donor>, bills: List<BillRow>) {
    Text("Donors", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(donors, key = { it.id }) { donor ->
            val donorBills = bills.filter { it.bill.donorId == donor.id }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(donor.name, fontWeight = FontWeight.Bold)
                    Text(donor.address)
                    Text(donor.contactNumber)
                    Text("Bills in selected event: ${donorBills.size} • Due ${money(donorBills.sumOf { it.duePaise })}")
                }
            }
        }
    }
}

@Composable
private fun MoreScreen(
    events: List<PujaEvent>, bills: List<BillRow>, payments: List<DonationPayment>, expenses: List<Expense>,
    onAddEvent: () -> Unit, onChooseEvent: (Long) -> Unit, onExpense: () -> Unit,
    onExport: () -> Unit, onImport: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Events", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        events.forEach { event ->
            OutlinedButton(onClick = { onChooseEvent(event.id) }, modifier = Modifier.fillMaxWidth()) {
                Text("${if (event.isCurrent) "✓ " else ""}${event.name} ${event.year}")
            }
        }
        Button(onClick = onAddEvent, modifier = Modifier.fillMaxWidth()) { Text("＋ Add event / year") }
        Divider()
        Text("Expenses", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Button(onClick = onExpense, modifier = Modifier.fillMaxWidth()) { Text("Add expense") }
        expenses.forEach { Text("${it.category}: ${it.description} • ${money(it.amountPaise)}") }
        Divider()
        Text("Reports • selected event", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Bills: ${bills.size}")
        Text("Total billed: ${money(bills.sumOf { it.bill.amountPaise })}")
        Text("Collected: ${money(bills.sumOf { it.receivedPaise })}")
        Text("Outstanding: ${money(bills.sumOf { it.duePaise })}")
        Text("Expenses: ${money(expenses.sumOf { it.amountPaise })}")
        Text("Cash recorded: ${money(payments.filter { it.method == "CASH" }.sumOf { it.cashPaise })}")
        Text("UPI recorded (unverified): ${money(payments.sumOf { it.upiPaise })}")
        Divider()
        Text("Local backup / restore", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Export full JSON backup") }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Restore JSON backup (replaces current data)") }
        Text("Keep your backup file safe. Restore replaces all existing local records.", color = DueRed)
        Text("Chandar Khata • Offline MVP 1.0.0")
    }
}

@Composable
private fun PaymentDialog(
    row: BillRow, onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, String, String) -> Unit
) {
    var amount by remember { mutableStateOf((row.duePaise / 100.0).toString()) }
    var method by remember { mutableStateOf("CASH") }
    var cash by remember { mutableStateOf("") }; var upi by remember { mutableStateOf("") }
    var collector by remember { mutableStateOf("") }; var ref by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collect payment • Due ${money(row.duePaise)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("CASH", "UPI_TEST", "MIXED").forEach {
                        FilterChip(selected = method == it, onClick = { method = it }, label = { Text(it) })
                    }
                }
                if (method == "MIXED") {
                    OutlinedTextField(cash, { cash = it }, label = { Text("Cash part ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(upi, { upi = it }, label = { Text("UPI test part ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                OutlinedTextField(collector, { collector = it }, label = { Text("Collector name") })
                if (method != "CASH") OutlinedTextField(ref, { ref = it }, label = { Text("UPI reference (manual/test only)") })
                if (method != "CASH") Text("UPI is recorded as UNVERIFIED. No bank/payment verification occurs.", color = DueRed)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(amount, method, cash, upi, collector, ref) }) { Text("Save payment") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ExpenseDialog(onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var category by remember { mutableStateOf("General") }; var desc by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }; var method by remember { mutableStateOf("CASH") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Add expense") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(category, { category = it }, label = { Text("Category") })
                OutlinedTextField(desc, { desc = it }, label = { Text("Description") })
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount ₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(method, { method = it }, label = { Text("Payment method (CASH/UPI)") })
            }
        },
        confirmButton = { Button(onClick = { onSave(category, desc, amount, method) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SimpleDialog(title: String, labels: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val values = remember { mutableStateListOf("", "") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, label ->
                    OutlinedTextField(values[i], { values[i] = it }, label = { Text(label) })
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(values.toList()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
