# ISO 8583 practice simulator

A small issuer host and terminal written in Java with jPOS. You can use it to practise card authorization testing on your own laptop:
- Sign-on, purchases, cash withdrawals and balance inquiries
- Declines, PIN tries, duplicates, timeouts and reversals
- International transactions and DCC (dynamic currency conversion)
- End-of-day clearing and settlement files for Visa, Mastercard, RuPay and Amex

Every message is printed field by field.

It is a learning tool, not a copy of Visa VTS or Mastercard MTF. Those are licensed tools for network members. This one follows the same ideas so you can practise the test thinking and talk about it in interviews.

## Requirements

- Java 17 or later (tested with Java 24)
- Maven 3.9

## Run it

Open two terminals in this folder.

**Terminal 1, the issuer host:**

```
run-host.cmd
```

**Terminal 2, the terminal / acquirer:**

```
run-terminal.cmd
```

Pick **1** to sign on first. Until you do, the host answers **91** to every transaction, the way a real switch rejects a terminal that hasn't signed on.

Both default to `localhost` port `8583`. To use another port: `run-host.cmd 9000` and `run-terminal.cmd localhost 9000`.

## Run the automated scenarios

```
mvn test
```

This runs 26 JUnit tests. Each test starts a fresh host on a free port, connects a terminal, signs on, and checks one scenario end to end over TCP. Reading these tests is a good way to see how ISO 8583 testing can be automated.
- `IssuerScenariosTest`: domestic authorization, declines, PIN, duplicates, reversals, timeout
- `InternationalAndFilesTest`: foreign currency, DCC, international on/off, and the end-of-day files

## Test cards

| # | Card | PAN | Expiry | PIN | Status | Intl | Balance (INR) | Per-txn limit (INR) |
|---|---|---|---|---|---|---|---|---|
| 1 | Visa, active | 4111111111111111 | 2812 | 1234 | Active | Yes | 25,000.00 | 10,000.00 |
| 2 | Visa, low balance | 4000000000000002 | 2812 | 4321 | Active | Yes | 500.00 | 10,000.00 |
| 3 | Mastercard, expired | 5555555555554444 | 2401 | 1111 | Active | Yes | 10,000.00 | 10,000.00 |
| 4 | Mastercard, blocked | 5105105105105100 | 2812 | 2222 | Blocked | Yes | 10,000.00 | 10,000.00 |
| 5 | RuPay, reported lost | 6521000000000017 | 2812 | 3333 | Lost | Yes | 10,000.00 | 10,000.00 |
| 6 | RuPay, reported stolen | 6521000000000025 | 2812 | 4444 | Stolen | Yes | 10,000.00 | 10,000.00 |
| 7 | RuPay, active | 6521000000000033 | 2812 | 5555 | Active | Yes | 15,000.00 | 10,000.00 |
| 8 | Amex, active | 378282246310005 | 2812 | 6666 | Active | Yes | 50,000.00 | 25,000.00 |
| 9 | Mastercard, intl off | 5200828282828210 | 2812 | 7777 | Active | **No** | 20,000.00 | 10,000.00 |

These are well-known public test numbers and made-up numbers. Never type a real card into any practice tool.

The host keeps balances in memory, so restarting it resets every card. The scheme is worked out from the PAN's first digits: 4 Visa; 51–55 and 2221–2720 Mastercard; 34/37 Amex; 60, 65, 81, 82, 508 RuPay.

## What the host checks, in order

1. **Format:** DE 2, 3, 4, 7, 11 and 41 must be present, or **30**
2. **Duplicate:** same terminal (DE 41) + STAN (DE 11) + DE 7 seen before, so **94**
3. **Card lookup:** unknown PAN gives **14**
4. **Status:** lost **41**, stolen **43**, blocked **62**
5. **Expiry:** card expired, or DE 14 does not match the card, gives **54**
6. **Processing code (DE 3):** only 00 purchase, 01 cash and 31 balance inquiry are supported, anything else gives **12**
7. **PIN (DE 52):** wrong PIN gives **55**; the 3rd wrong try gives **75** and locks the PIN. Cash and balance inquiry need a PIN, so a missing DE 52 gives **55**
8. **International use:** a foreign currency (DE 49 ≠ 356) or a merchant outside India (DE 19 ≠ 356) on a card with international use turned off gives **57**
9. **Amount and currency:**
   - Zero gives **13**; an unsupported currency gives **13**
   - A foreign currency without DE 6 or DE 10 gives **30**
   - The INR billing amount over the per-transaction limit gives **61**
   - Billing amount plus issuer fees over the available balance gives **51**
10. **Approve with 00:** DE 38 auth code, DE 54 balances
    - A 0100 purchase **places a hold**: available drops, ledger does not (dual message)
    - A 0200 withdrawal **posts at once**: ledger and available both drop (single message)

Reversals (0400/0420) are matched to the original using DE 90:

- Not found: **25**
- Already reversed: **00**, and nothing changes (duplicate reversal)
- Already settled at the end-of-day cutover: **12**. After settlement the money has moved, so the correction is a refund or a chargeback, not a reversal
- Full reversal: the hold is released (or, for a 0200, the money is credited back), including the issuer's fee
- Partial reversal: DE 95 holds the amount that stays charged, in the transaction currency. The billing amount and fee are reduced in proportion

## Scenarios to try from the terminal

### Domestic

| Try this | Expect |
|---|---|
| Purchase before signing on | 91 |
| 1 sign-on, then 2 echo test | 0810 with 00 |
| Purchase INR 1,500 on card 1, then balance inquiry | 00; available 23,500.00 but ledger still 25,000.00 |
| Cash withdrawal INR 2,000 on card 1, then balance inquiry | 00; ledger and available both drop |
| Purchase INR 1,000 on card 2 | 51 |
| Purchase on card 3 | 54 |
| Purchase on card 1 with expiry 2711 | 54 (expiry mismatch) |
| Purchase on cards 4, 5, 6 | 62, 41, 43 |
| Purchase on PAN 4999999999999999 | 14 |
| Purchase INR 15,000 on card 1 | 61 |
| Cash withdrawal with PIN 9999 three times, then with 1234 | 55, 55, 75, 75 |
| Purchase, then option 9 (resend unchanged) | 00, then 94 |
| Purchase INR 1,000, then option 7 (full reversal), then 7 again | 00, 0430 00, then 00 with nothing changed |
| Purchase INR 1,000, then option 8 keeping INR 600 | Available goes back up by 400 |
| Option 10 | 25 |
| **Purchase of exactly INR 777.00** | See "The timeout scenario" |

### International and DCC

| Try this | Expect |
|---|---|
| Option 11, card 1, USD 100.00 | 00. DE 6 = INR 8,400.00, plus forex markup INR 294.00; hold INR 8,694.00 |
| Option 12, card 1, AED 200.00, PIN 1234 | 00. INR 4,574.00 + INR 160.09 markup posted to the ledger |
| Option 11, card 1, USD 150.00 | 61 (INR 12,600.00 is over the INR 10,000.00 limit) |
| Option 11, card 2, AED 21.50 | 51. INR 491.71 fits the INR 500.00 balance, but not with the markup (INR 508.92) |
| Option 11 or 13 on card 9 | 57 (international use is off) |
| Purchase on card 9 in INR | 00 (domestic is fine) |
| Option 13, card 1, EUR 50.00, choose 1 | Charged in EUR: about INR 4,735.13 including markup |
| Option 13, card 1, EUR 50.00, choose 2 | Charged in INR at the merchant's DCC rate: about INR 4,805.58 including the cross-border fee |
| Option 11, then option 7 | The whole hold is released, including the markup |
| Option 14 | Cutover, which writes the end-of-day files below |

### The timeout scenario

A purchase or withdrawal of exactly INR 777.00 is approved by the host, but the response is sent 5 seconds late. The terminal waits only 3 seconds, so:

1. The terminal gives up and doesn't know whether the issuer approved.
2. It sends an **0420 reversal advice** with **DE 39 = 68** (response received too late) and DE 90 pointing at the original.
3. The late **0110** arrives and the terminal **discards** it, because it is waiting for the 0430.
4. The host processes the 0420 and **releases the hold**.

Do a balance inquiry afterwards: the money is back. This is one of the most common interview scenarios ("what happens if the network times out after the issuer debits the card?") and you can now show it.

## International transactions

When an Indian card is used abroad in the local currency:

| Field | Example (USD 100.00 in New York) | Who sets it |
|---|---|---|
| DE 4 Transaction amount | `000000010000` = USD 100.00 | Acquirer |
| DE 49 Transaction currency | `840` (USD) | Acquirer |
| DE 19 Acquiring institution country | `840` (US) | Acquirer |
| DE 43 Merchant name / location | `PRACTICE STORE  NEW YORK  US` | Acquirer |
| DE 6 Cardholder billing amount | `000000840000` = INR 8,400.00 | Network |
| DE 10 Conversion rate | `40840000` = 84.0000 (first digit = decimal places) | Network |
| DE 51 Billing currency | `356` (INR) | Network |

The issuer then adds its own **forex markup** (3.5% here) on top of DE 6. It checks the limit against DE 6 and the funds against DE 6 plus the markup.

In this simulator the terminal fills DE 6, DE 10 and DE 51 itself, playing the network's part.

Practice exchange rates (INR per unit): USD 84.0000, EUR 91.5000, GBP 108.2000, AED 22.8700, SGD 63.1000. These are fixed made-up rates, not live ones.

## DCC (dynamic currency conversion)

DCC is when a merchant abroad offers to charge you in your **home currency** (INR) instead of theirs. The merchant's DCC provider converts at its own rate, which includes a margin (4% here).

**On the message:**
- DE 4 is in INR and **DE 49 = 356**, because the cardholder chose INR.
- **DE 19 and DE 43 still show a foreign merchant.** It is a cross-border transaction even though the currency is INR.
- **No DE 6 or DE 10 from the network:** the conversion already happened at the terminal.
- DCC details travel in DE 48 in this practice layout: `DCC` + original currency + original amount + DCC rate + margin. Real networks carry DCC indicators in their own network-specific fields, so check your scheme's specification.

**On the issuer side:**
- **No forex markup,** because there is no currency to convert.
- Many Indian banks still charge a **cross-border fee on INR transactions at foreign merchants.** This simulator uses 1%; check your bank's actual fee schedule.

**Why it matters in interviews:**
- The cardholder can pay **twice**: the merchant's DCC margin plus the bank's cross-border fee. Option 13 prints both prices side by side.
- Testers check that DCC transactions are flagged correctly. They should get the cross-border fee, not the forex markup, and international-off cards must still decline them.

## End-of-day files

Option 14 sends **0800 with DE 70 = 201 (cutover)**. The host closes the business day, marks the day's approved transactions as settled, and writes these files to the `output` folder:

| File | What it is | Contents |
|---|---|---|
| `AUTHLOG_<yyyyMMdd>_<seq>.csv` | The issuer's own authorization log (the "switch log") | Every request and decision since the last cutover, approved and declined |
| `VISA_BASEII_CLEARING_<date>_<seq>.txt` | Visa incoming clearing (BASE II) | One `TC05` record per cleared Visa purchase |
| `VISA_VSS_SETTLEMENT_<date>_<seq>.txt` | Visa settlement summary (VSS) | Totals, interchange, net amount the issuer pays Visa |
| `MC_IPM_T112_CLEARING_<date>_<seq>.txt` | Mastercard incoming clearing (IPM, T112) | One `1240-200` (first presentment) record per purchase |
| `MC_SETTLEMENT_REPORT_<date>_<seq>.txt` | Mastercard settlement position | Same totals as above for Mastercard |
| `RUPAY_CLEARING_<date>_<seq>.txt` | RuPay (NPCI) incoming clearing | One record per RuPay purchase |
| `RUPAY_NTSL_<date>_<seq>.txt` | RuPay NTSL (net settlement report) | Net amount for RuPay |
| `AMEX_SUBMISSION_<date>_<seq>.txt` | Amex submission / clearing detail | One record per Amex purchase |
| `AMEX_SETTLEMENT_<date>_<seq>.txt` | Amex settlement summary | Net amount for Amex |

`<seq>` is `01` for the first cutover of the day, `02` for the next, and so on. Schemes with no transactions still get a file with zero records, the way real networks send empty files.

**Clearing files** use a pipe-delimited practice layout:

```
H|VISA|Visa BASE II incoming clearing (practice layout)|BUSINESS_DATE=2026-10-26|FILE_SEQ=01
# D|TXN_CODE|ARN|PAN|AUTH_CODE|RRN|AUTH_DATE_TIME|MCC|MERCHANT|ACQ_COUNTRY|TXN_CCY|TXN_AMOUNT|BILLING_CCY|BILLING_AMOUNT|CONVERSION_RATE|DCC
D|TC05|24000016299000000000014|411111******1111|693362|629923000002|2026-10-26 23:23:56|5411|PRACTICE STORE|840|840|100.00|356|8400.00|84.0000|N
D|TC05|24000016299000000000022|411111******1111|188936|629923000003|2026-10-26 23:23:56|5411|PRACTICE STORE|276|356|4758.00|356|4758.00||Y
T|DETAIL_RECORDS=2|TOTAL_BILLING_AMOUNT=13158.00
```

Things to notice:
- **Only 0100 purchases appear in clearing files.** 0200 cash withdrawals are single message: they were posted when authorized, so they appear only in the settlement report.
- **Reversed transactions don't appear.** A partially reversed one appears with the amount that stayed charged.
- **The ARN** (acquirer reference number) is 23 digits: format digit + acquirer BIN + date (YDDD) + sequence + Luhn check digit. Chargebacks and retrievals use it as the key.
- **The forex markup and cross-border fee are not in the clearing or settlement files.** They are the issuer's own income from the cardholder.
- **Settlement:** issuer pays = purchases + cash + ATM interchange (INR 19.00 per withdrawal) − purchase interchange earned (1.5%). These are practice rates.

### What the real files are called

Bank and processor teams usually know these files by the following names. Names, layouts and delivery differ by network, region and the services your bank subscribes to, so confirm against the specifications you work with.

| Scheme | Clearing | Settlement and reports | Reference data |
|---|---|---|---|
| **Visa** | BASE II incoming and outgoing clearing files, with transaction codes (TC05 sales, TC06 credits, TC07 cash, TC15–17 chargebacks, TC25–27 reversals) | VSS reports such as VSS-110 (settlement summary), VSS-120 (interchange), VSS-130/140 (fees), often delivered in the Edit Package report file | BIN and account range tables |
| **Mastercard** | IPM files through GCMS, commonly known by the bulk type T112. Binary ISO 8583:1993 records: 1240 presentments, 1442 chargebacks, 1644 file header/trailer, 1740 fees | Settlement position and reconciliation reports, delivered as separate bulk files | MPE (Member Parameter Extract) |
| **RuPay (NPCI)** | Domestic clearing raw data files per settlement cycle; RGCS for RuPay global clearing and settlement | NTSL (net settlement report) per cycle; NFS raw data and NTSL for ATM | NPCI BIN and participant files |
| **Amex** | Submission files from acquirers; Amex is closed loop, often issuer and acquirer at once | Settlement and reconciliation files, e.g. the GRRCN reconciliation file for merchants | — |

Real files are fixed-width or binary, not pipe-delimited. Use this simulator's files to practise the ideas: header/detail/trailer structure, record counts, hash totals, matching keys and net settlement.

## Reading the output

Each message prints like this:

```
>>> SENT  0100  Authorization request
    Bitmap  723C448108E08000  -> DEs 2,3,4,7,11,12,13,14,18,22,25,32,37,41,42,43,49
    DE 2   PAN - PRIMARY ACCOUNT NUMBER       411111******1111
    DE 3   PROCESSING CODE                    [000000]
    DE 4   AMOUNT, TRANSACTION                [000000150000]
    ...
```

Practise decoding the bitmap by hand: `7` = `0111`, so bits 2, 3 and 4 are on; `2` = `0010`, so bit 7 is on; and so on. If the first hex digit is 8 or more, bit 1 is on and a secondary bitmap follows (you'll see that on 0800, which carries DE 70).

Details worth noticing:
- **DE 7 is GMT and DE 12/13 are local time,** so they can show different dates.
- **DE 4 is in the minor unit:** `000000150000` = INR 1,500.00.
- **The response drops DE 14 and DE 52,** because sensitive data isn't echoed back.
- **DE 54 holds two 20-character balance entries:** account type + amount type (01 ledger, 02 available) + currency 356 + C/D + 12-digit amount.
- **DE 52 is a real ISO format 0 PIN block,** but it isn't encrypted here. In production the terminal encrypts it under the PIN key and the issuer's HSM verifies it.

## How this differs from real network simulators

| This tool | Real VTS / MTF / RuPay simulators |
|---|---|
| ISO 8583:1987 ASCII layout (jPOS ISO87APackager) for every scheme | Each network has its own spec: Visa BASE I headers and fields 62/63, Mastercard DE 48 subelements and DE 61, RuPay's own field usage; Amex uses 1993-version MTIs |
| 2-byte binary length header only | Network-specific headers |
| Clear PIN block, no keys | ZMK/ZPK key exchange, encrypted PIN blocks, HSM |
| No EMV (DE 55) | ARQC validation, ARPC in the response |
| Fixed practice exchange rates and fees | Daily network rates, real interchange tables |
| Terminal fills DE 6 and DE 10 | The network converts and adds them |
| Pipe-delimited practice files | Fixed-width or binary files in each network's format |
| Balances in memory | Card management system and core banking |

## Practice exercises

Each one is a small, realistic change. Write a JUnit test for it first, then change the code until the test passes.

1. **Refund (DE 3 = 20):** credit the card instead of declining with 12, and write it to the clearing file as a credit (Visa TC06).
2. **Pre-auth completion:** accept an 0220 advice that turns a hold into a posted debit, for a final amount up to 20% above the hold (the hotel and fuel tolerance).
3. **Daily cash limit:** decline with 61 once withdrawals in one day go over INR 20,000, and with 65 after 5 withdrawals in a day.
4. **MCC blocking:** decline gambling (MCC 7995) with 57 (transaction not permitted to cardholder).
5. **Check the network's conversion:** in `AuthEngine`, decline with 30 when DE 6 is not equal to DE 4 × DE 10.
6. **Stand-in advice:** accept an 0120 for a transaction the network approved while the issuer was down, and post it even when the balance is short.
7. **Reconciliation:** write a job that reads `AUTHLOG_*.csv` and the four clearing files, matches approved 0100s to clearing records by RRN and auth code, and prints matched, unmatched and amount-mismatch lines. Then edit an amount in a clearing file by hand and check that your job catches it.

## Talking about it in an interview

You can say something like:

> "To keep my ISO 8583 skills sharp, I built a small issuer host and terminal with jPOS. It handles sign-on, 0100 and 0200, reversals with DE 90 and DE 95, duplicate detection, PIN tries and the timeout-then-reversal flow. It also covers international transactions with DE 6, DE 10 and the forex markup, DCC, and an end-of-day cutover that writes clearing and settlement files for Visa, Mastercard, RuPay and Amex. I automated 26 scenarios with JUnit over TCP."

Then be ready to explain any field, response code, scenario or file in this README.

## Project layout

```
src/main/java/practice/iso/
  IssuerHost.java      TCP server: receive, decide, respond
  AuthEngine.java      The issuer's decision logic (start reading here)
  SchemeFiles.java     End-of-day auth log, clearing and settlement files
  Scheme.java          Visa / Mastercard / RuPay / Amex by PAN, and their file names
  Transaction.java     An approved transaction kept for reversals and files
  Card.java            One test card
  CardStore.java       The nine test cards
  PinBlock.java        ISO format 0 PIN block
  Terminal.java        Interactive menu
  TerminalClient.java  Connection, response matching, late-response handling
  MessageFactory.java  Builds 0800, 0100, 0200, international, DCC and 0420 messages
  MessagePrinter.java  Prints MTI, bitmap and every data element
  Iso.java             Packager, channel, currencies, fees, response code names
src/test/java/practice/iso/
  IssuerScenariosTest.java        15 domestic scenarios
  InternationalAndFilesTest.java  11 international, DCC and file scenarios
```
