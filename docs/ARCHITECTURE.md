# Architecture notes

This document covers the decisions that are easy to get wrong and expensive to
change later. The overview is in the [README](../README.md).

## 1. Sources, events, transactions

Three layers, and conflating any two of them is how expense trackers break:

| Layer | Type | Meaning |
| --- | --- | --- |
| Source | `SourceRef` | A message that exists on the phone. |
| Event | `CandidateTransaction` | One message's *claim* about money moving. |
| Transaction | `CanonicalTransaction` | What actually happened. |

A transaction holds every source that evidenced it. Merging a duplicate moves its
evidence, it never deletes it, so the app can say "confirmed from 2 messages" and
a wrong merge can be undone from facts rather than guesses.

## 2. Classification comes before parsing

The classifier labels a message before anything reads its numbers, and the order
of its rules is load-bearing:

1. **OTP** — first, because an OTP body may quote the amount it authorises.
2. **Marketing** — but a transactional message with a `T&C apply` footer survives.
3. **Mandate / future tense** — `will be debited on 05-Sep` is a warning, not a debit.
4. **Failure, reversal, refund** — before any generic debit or credit rule.
5. **Card bill payment** — before the generic credit rule, or it books as income.
6. **Statement / balance alert** — amounts with no movement.
7. **Movement**, most specific rail first: ATM, salary, interest, cashback, EMI,
   investment, bill, card, UPI, NEFT/IMPS/RTGS, then plain debit/credit.

Messages that are not transactions are still recorded, with their class, so the
app can account for every message it read.

## 3. Amount roles

Every rupee figure is classified by the words around it:

| Role | Triggered by | Booked? |
| --- | --- | --- |
| `TRANSACTION` | adjacency to a movement verb | yes |
| `BALANCE` | `Avl Bal`, `A/c balance` | stored as last-known balance |
| `AVAILABLE_LIMIT` / `CREDIT_LIMIT` | `Avl limit`, `credit limit` | card metadata |
| `TOTAL_DUE` / `MINIMUM_DUE` / `STATEMENT` | `Total amount due`, `Min due` | card metadata |
| `REWARD` | `cashback of` | income, not spending |

`Rs.5,000 debited … Avl Bal Rs.45,900` books ₹5,000. A statement message books
nothing at all.

## 4. Duplicate scoring

`DuplicateDetector` scores a pair of transactions:

| Signal | Score |
| --- | --- |
| Same rail reference (UPI ref / UTR / RRN) | +100 |
| Same amount | +35 (a different amount is an immediate reject) |
| Within 2 minutes | +25 |
| Within 5 minutes | +15 |
| Within 15 minutes | +5 |
| More than 2 hours apart | −40 |
| Same account | +20 |
| Different known accounts | −25 |
| Same merchant | +20 |
| Different known merchants | −20 |
| Compatible payment method | +10 (incompatible: −15) |
| Reported by different senders | +10 |
| **Repeat-purchase guard**: no shared reference and more than 15 minutes apart | −30 |

Thresholds: **≥ 75 merge**, **45–74 ask**, **< 45 keep separate**. Two different
UPI references of the same kind are an immediate reject regardless of everything
else — that is two payments, not one.

The guard is what separates "the bank and Google Pay both told me about one
payment" from "I bought the same coffee twice".

## 5. Fingerprints

Matching candidates are found by fingerprint before they are scored. There are
three, because every message omits something different:

- `exact` — the normalized rail reference.
- `soft` — account + amount + merchant + a 5-minute bucket + direction.
- `coarse` — amount + merchant + day + direction.

A bank SMS has the account but no merchant; a merchant app has the merchant but
no account; a wallet has neither but does have the reference. One hash would fail
whenever its input was the missing field.

## 6. Relationship detection, in order

Order matters: a credit landing on a credit card is a bill payment before it is
ever a transfer.

| Detector | Window | Result |
| --- | --- | --- |
| Card payment | 3 days | both legs become `CARD_PAYMENT`, linked `CARD_PAYMENT_FOR`; neither is spending or income |
| Reversal | 7 days | original → `REVERSED` (net ₹0), linked `REVERSAL_OF` |
| Refund | 90 days | original → `REFUNDED` / `PARTIALLY_REFUNDED`, `refundedAmount` accrues |
| Transfer | 6 hours | both legs `TRANSFER`, linked `TRANSFER_PAIR` |

Refunds only attach to a purchase from the same merchant, or — with no merchant
match — to an exact-amount, same-account purchase. Anything looser is guessing
with someone's money.

## 7. Self-accounts

Transfer detection is only as good as knowing which accounts are the user's.
Accounts are discovered from the messages themselves (an account repeatedly
named, especially with a balance attached, is almost certainly the user's) and
recorded as unverified observations. Confirming one on the Accounts screen makes
it transfer-eligible and re-runs reconciliation over the recent window, because
that answer can turn what looked like income into a transfer.

## 8. UPI apps are not accounts

If a Google Pay message names no bank account, the transaction is recorded with
`channelApp = "Google Pay"` and **no account** — not an invented Google Pay
balance. If the message does name the funding bank, that bank wins. Wallets that
genuinely hold money (Paytm balance, Amazon Pay balance) are accounts.

## 9. Time

Indian SMS date formats are all parsed to an instant in IST: `31-Aug-26`,
`31/08/2026`, `31-08-26`, `31AUG26`, `Aug 31`, `2026-08-31`, with 12- and 24-hour
clocks. Two guards apply:

- Nothing may be dated more than a day *after* the message that reports it, so a
  due date is never mistaken for a transaction time.
- Nothing may be dated 400 days *before* it either.

When the body carries no date the SMS receive time is used and the row is flagged
`MESSAGE_RECEIVED_TIME`, so the UI can say "time approximate" instead of quietly
inventing precision. Balances get the same treatment: they are always shown as
"last known", with when they were learnt.

## 10. Parser versioning

Every source records the parser version that produced it. When the rules improve,
`ReprocessWorker` re-reads messages already on the phone and rebuilds the rows
whose parser is out of date — so last year's `Merchant: Unknown` can become
`Indian Oil` locally, with no cloud copy of anyone's SMS. Transactions the user
has edited, and transactions several messages corroborate, are left alone.

## 11. Storage

| Table | Purpose |
| --- | --- |
| `transactions` | canonical rows |
| `transaction_sources` | the evidence trail |
| `transaction_links` | duplicate / reversal / refund / transfer / card-payment edges |
| `accounts` | discovered and confirmed accounts, balances, card dues |
| `merchants`, `merchant_aliases` | canonical merchant identity |
| `rules` | corrections the user made once |
| `recurring_patterns` | detected commitments |
| `review_items` | the queue |
| `processed_messages` | every message ever read, by hash, with parser version |
| `raw_messages` | message text, only if the user turns retention on |

Enums are stored as strings, not ordinals: reordering a Kotlin enum must never
silently reinterpret someone's history, and an export should be readable.

## 12. Why the engine has no Android in it

`:engine` has no Android imports, no I/O, no clock and no randomness. Parsing
rules are the part of this product most likely to change weekly, and they can be
tested against a corpus of real message shapes in milliseconds — the whole suite
runs on a laptop with no emulator. The Android module owns permissions, storage,
scheduling and pixels, and nothing else.
