# Kharcha — an offline expense app built for Indian banking

Kharcha reconstructs your financial life from the SMS your banks, cards, UPI apps
and wallets already send you. It runs entirely on the phone: no login, no bank
connection, no server, and **no `INTERNET` permission in the manifest**, so
nothing it reads is able to leave the device even by accident.

It is not another manual expense tracker. It is a reconciliation engine.

---

## The one idea everything else follows from

A naive tracker treats every financial SMS as an expense. In India that is wrong
almost immediately. One ₹540 Swiggy order can produce three messages:

```
HDFC Bank   Rs.540.00 debited from A/c XX4821 towards UPI-SWIGGY. UPI Ref 425678901234
Google Pay  You paid Rs.540.00 to Swiggy using Google Pay. UPI Ref 425678901234
Swiggy      Payment of Rs.540.00 successful. UPI Ref 425678901234
```

Booked naively that is ₹1,620 of spending. Kharcha is built the other way round:

```
many messages  →  financial events  →  reconciliation  →  one real transaction
```

Get that right and a whole class of Indian-specific problems disappears with it:
duplicate reporting, self-transfers, credit-card bill payments, ATM withdrawals,
failed payments, reversals and refunds.

---

## What it actually handles

**Messages that must never become expenses.** OTPs (including the ones that quote
the amount they authorise), marketing, balance alerts, card statements, and
mandate notices about money that has not moved yet. Every message is classified
before anything looks at its numbers.

**Amounts that are not the amount.** A card SMS routinely carries the spend, the
available balance, the total due and the minimum due. Each number is assigned a
role, so a ₹45,900 balance is never booked as a ₹45,900 purchase.

**Duplicates, without merging real repeat purchases.** Matching is scored across
several signals — rail reference, amount, time proximity, account, merchant,
payment method, and whether the two reports came from different reporters. Two
coffees of ₹100 from the same shop two hours apart stay two purchases; the same
₹540 reported by a bank and a UPI app becomes one.

**Transfers between your own accounts.** ₹10,000 swept from HDFC to SBI is one
transfer, not ₹10,000 spent plus ₹10,000 earned. The app discovers your accounts
from your own messages and asks once.

**Credit cards as liabilities.** A ₹4,500 Amazon spend on a card is an expense.
The ₹20,000 you later pay towards that card is not a second expense — it is a
liability payment, linked to the card credit that acknowledges it.

**Cash.** An ATM withdrawal moves money from the bank into a cash wallet. It is
not spending until you spend it, so withdrawing ₹10,000 and spending it does not
become ₹20,000.

**Reversals and refunds, which are different things.** A reversed payment nets to
zero and the original is marked reversed. A ₹1,500 refund against a ₹4,000
purchase leaves a net expense of ₹2,500, not zero and not ₹4,000.

**Investments separated from consumption.** A ₹10,000 SIP has not disappeared,
and reporting it as spending makes the month look worse than it was.

**UPI apps as channels, not accounts.** A Google Pay payment is recorded with
Google Pay as the channel and your bank as the funding account — never as a
balance in an app that holds none.

**Merchant identity.** `UPI/SWIGGY/4256`, `RAZORPAY*SWIGGY`, `SWIGGYUPI` and
`BUNDL TECHNOLOGIES` all resolve to Swiggy, so categories are learned once
rather than per spelling.

**A review queue instead of interruptions.** Anything genuinely uncertain — a
possible duplicate, an unknown account, an ambiguous transfer — goes into one
"Needs review" list with the reasons attached. Your answer becomes a local rule.

---

## Screens

| Screen | What it is for |
| --- | --- |
| **Home** | Money in, spent, invested and what is actually left, with transfers and card bills excluded from spending. Accounts with last-known balances, top categories, UPI and small-payment insights, monthly commitments. |
| **Activity** | Everything, grouped by day, with a filter for hiding transfers and bills. |
| **Detail** | The evidence behind a row: which messages produced it, the parser version, and the graph of related transactions (refund, reversal, transfer pair, card payment). |
| **Review** | The queue of uncertain items, each with its reasoning and two or three answers. |
| **Accounts** | Confirm which accounts are yours; card limits, statement dues and due dates. |
| **Search** | Offline queries like `food above 500 last month`, `upi payments this week`, `credit card spending`. |
| **Insights** | Where your salary went, repeating payments, net position, and an honest count of what the app read and deliberately ignored. |
| **Settings** | Message-text retention (off by default), app lock, and the privacy position in plain language. |

---

## Architecture

```
SMS inbox
    │
    ▼
Financial message classifier ──► OTP / promo / balance / statement / mandate → recorded, not booked
    │
    ▼
Parser: amount roles · Indian dates · masked accounts · UPI-UTR-RRN refs · merchant descriptors
    │
    ▼
Candidate transactions (observations, not ledger entries)
    │
    ▼
Reconciliation ── duplicates ── transfers ── card payments ── reversals ── refunds
    │
    ▼
Canonical transactions + evidence + link graph
    │
    ▼
Encrypted Room database ──► dashboard · activity · review · search · insights
```

Two modules:

- **`:engine`** — pure Kotlin/JVM. No Android, no I/O, no clock, no randomness.
  Everything that decides *what a message means* lives here, which is why it can
  be tested exhaustively in milliseconds. 66 unit tests cover the classifier,
  every extractor, merchant normalization, the duplicate scoring engine, and each
  double-counting trap end to end.
- **`:app`** — Android: encrypted Room storage, SMS ingestion, WorkManager,
  Hilt, and a Compose/Material 3 UI.

Deeper design notes, including the duplicate scoring table and the matching
windows, are in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## Privacy

- **No `INTERNET` permission.** Not "we promise not to upload" — the app cannot.
- **The database is encrypted** (SQLCipher) with a per-install random key held in
  a Keystore-backed store.
- **Message text is not retained by default.** The app keeps what it extracted
  plus a hash of each message, which is enough to stay idempotent and to prove it
  has seen something. Turning retention on is a setting; turning it off deletes
  what was kept.
- **SMS text never reaches WorkManager**, whose task database is unencrypted. The
  receiver only signals that something arrived.
- **Backups are excluded**, because an unencrypted copy in the cloud would undo
  the whole point.
- No analytics, no crash reporting, no account.

---

## Building

Requires JDK 17+ and the Android SDK (compileSdk 35).

```bash
./gradlew :engine:test        # the reconciliation test suite
./gradlew :app:assembleDebug  # the APK
```

The engine module builds and tests on any JDK 17+ with no Android SDK present,
which is the intended way to work on parsing rules.

## Status

The `:engine` module is complete and its test suite passes. The `:app` module is
complete in source and is built with the Android SDK — it has not been assembled
or run on a device in the environment this repository was written in, so treat
the first `assembleDebug` and an on-device pass as the remaining verification
step.

Not built yet, and deliberately scoped out of this pass: notification-based
ingestion (Google Pay/PhonePe/Paytm posts), receipt OCR reconciliation (the ML
Kit dependency is wired but no scanner screen exists), budgets UI, and encrypted
export/import.
