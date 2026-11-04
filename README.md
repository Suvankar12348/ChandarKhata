# Chandar Khata (চাঁদার খাতা) — Offline Android MVP

This is a native Android starter project for Puja and event donation collection.

## Included
- Kotlin + Jetpack Compose + Material 3
- Room / SQLite local database
- No INTERNET permission and no cloud/server dependency
- Event/year selection
- New entry with exactly four fields: name, address, contact/WhatsApp, amount
- Existing donor matching by contact number
- Separate bill and payment records
- Partial/full payment with transaction-level due validation
- Cash, manual UPI test (always unverified), and mixed payment
- Expense entry and basic event-level totals
- Full JSON export and transactional restore preserving IDs and relationships

## Important limitations of this MVP
This is a functional foundation, not a production-ready release:
- PDF receipt generation/sharing is not yet included.
- CSV export, volunteer management, cash handover, advanced date-wise reports and Bengali language switching are not yet included.
- Donor matching is by normalized phone number only; when a donor is found, existing donor details are retained rather than overwritten.
- UI is bilingual in places but not a complete Bengali translation.
- UPI entries are manual test records only. They are never bank-verified.
- No multi-user sync, area management, GPS, cloud backup, login or server is included.

## Open and build
1. Install Android Studio (a current stable version) on Windows.
2. Extract this ZIP.
3. Android Studio → Open → select the `ChandarKhata` folder.
4. Allow Gradle sync and install any requested Android SDK 35 components.
5. Connect your Android phone with USB debugging enabled, then press Run.
6. To create an APK: Build → Build Bundle(s) / APK(s) → Build APK(s).

The first Gradle sync/build requires internet to download Android build dependencies. The installed app itself has no INTERNET permission and its core features work offline.

## Suggested first test
1. Create an event (e.g. Durga Puja 2026) from More → Add event.
2. Create a new chanda entry.
3. Record a partial cash payment, then the remaining amount.
4. Add an expense and inspect event totals.
5. Export a JSON backup.
6. Add a temporary record, restore the backup, and verify the original records return exactly.
7. Enable Airplane Mode and repeat normal data entry and payment testing.

## Money handling
Amounts are stored as integer paise rather than floating-point rupees. The DAO validates positive payment values, split totals, and the maximum outstanding amount inside a Room transaction.

## Backup behavior
Restore is a replace-all operation. It validates the backup relationships and then replaces all current event, donor, bill, payment and expense records in a single database transaction. Keep a separate copy of your backup file.

## Package
`com.chandarkhata.app`

## Version
`1.0.0-offline`
