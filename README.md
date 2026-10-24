# ISO 8583 practice simulator

A small issuer host and terminal written in Java with jPOS. You can use it to practise card authorization testing on your own laptop: sign-on, purchases, cash withdrawals, balance inquiries, declines, PIN tries, duplicates, timeouts and reversals. Every message is printed field by field.

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

This runs 15 JUnit tests in `src/test/java/practice/iso/IssuerScenariosTest.java`. Each test starts a fresh host on a free port, connects a terminal, signs on, and checks one scenario end to end over TCP. Reading these tests is a good way to see how ISO 8583 testing can be automated.

## Test cards

| # | Card | PAN | Expiry | PIN | Status | Balance (INR) | Per-txn limit (INR) |
|---|---|---|---|---|---|---|---|
| 1 | Visa, active | 4111111111111111 | 2812 | 1234 | Active | 25,000.00 | 10,000.00 |
| 2 | Visa, low balance | 4000000000000002 | 2812 | 4321 | Active | 500.00 | 10,000.00 |
| 3 | Mastercard, expired | 5555555555554444 | 2401 | 1111 | Active | 10,000.00 | 10,000.00 |
| 4 | Mastercard, blocked | 5105105105105100 | 2812 | 2222 | Blocked | 10,000.00 | 10,000.00 |
| 5 | RuPay, reported lost | 6521000000000017 | 2812 | 3333 | Lost | 10,000.00 | 10,000.00 |
| 6 | RuPay, reported stolen | 6521000000000025 | 2812 | 4444 | Stolen | 10,000.00 | 10,000.00 |

These are well-known public test numbers and made-up numbers. Never type a real card into any practice tool.

The host keeps balances in memory, so restarting it resets every card.

## What the host checks, in order

1. **Format:** DE 2, 3, 4, 7, 11 and 41 must be present, or **30**
2. **Duplicate:** same terminal (DE 41) + STAN (DE 11) + DE 7 seen before, so **94**
3. **Card lookup:** unknown PAN gives **14**
4. **Status:** lost **41**, stolen **43**, blocked **62**
5. **Expiry:** card expired, or DE 14 does not match the card, gives **54**
6. **Processing code (DE 3):** only 00 purchase, 01 cash and 31 balance inquiry are supported, anything else gives **12**
7. **PIN (DE 52):** wrong PIN gives **55**; the 3rd wrong try gives **75** and locks the PIN. Cash and balance inquiry need a PIN, so a missing DE 52 gives **55**
8. **Amount:** zero gives **13**, over the per-transaction limit gives **61**, over the available balance gives **51**
9. **Approve with 00:** DE 38 auth code, DE 54 balances
   - A 0100 purchase **places a hold**: available drops, ledger does not (dual message)
   - A 0200 withdrawal **posts at once**: ledger and available both drop (single message)

Reversals (0400/0420) are matched to the original using DE 90:

- Not found: **25**
- Already reversed: **00**, and nothing changes (duplicate reversal)
- Full reversal: hold released, or money credited back for a 0200
- Partial reversal: DE 95 holds the amount that stays charged; only the difference is released

## Scenarios to try from the terminal

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
| **Purchase of exactly INR 777.00** | See below |

### The timeout scenario

A purchase or withdrawal of exactly INR 777.00 is approved by the host, but the response is sent 5 seconds late. The terminal waits only 3 seconds, so:

1. The terminal gives up and doesn't know whether the issuer approved.
2. It sends an **0420 reversal advice** with **DE 39 = 68** (response received too late) and DE 90 pointing at the original.
3. The late **0110** arrives and the terminal **discards** it, because it is waiting for the 0430.
4. The host processes the 0420 and **releases the hold**.

Do a balance inquiry afterwards: the money is back. This is one of the most common interview scenarios ("what happens if the network times out after the issuer debits the card?") and you can now show it.

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
| ISO 8583:1987 ASCII layout (jPOS ISO87APackager) | Each network has its own spec: Visa BASE I headers and fields 62/63, Mastercard DE 48 subelements and DE 61, RuPay's own field usage |
| 2-byte binary length header only | Network-specific headers |
| Clear PIN block, no keys | ZMK/ZPK key exchange, encrypted PIN blocks, HSM |
| No EMV (DE 55) | ARQC validation, ARPC in the response |
| Balances in memory | Card management system and core banking |
| One host, one terminal | Network in the middle, stand-in processing, advices |

## Practice exercises

Each one is a small, realistic change. Write a JUnit test for it first, then change `AuthEngine.java` until the test passes.

1. **Refund (DE 3 = 20):** credit the card instead of declining with 12.
2. **Pre-auth completion:** accept an 0220 advice that turns a hold into a posted debit, for a final amount up to 20% above the hold (the hotel and fuel tolerance).
3. **Daily cash limit:** decline with 61 once withdrawals in one day go over INR 20,000, and with 65 after 5 withdrawals in a day.
4. **MCC blocking:** decline gambling (MCC 7995) with 57 (transaction not permitted to cardholder).
5. **International transactions:** send DE 49 = 840 (USD) and decline with 57 when the card has international use turned off.
6. **Stand-in advice:** accept an 0120 for a transaction the network approved while the issuer was down, and post it even when the balance is short.
7. **Clearing file:** write a small job that reads a CSV of cleared transactions, matches each one to an approved authorization by RRN and auth code, and prints matched, unmatched and amount-mismatch lines. That is reconciliation in miniature.

## Talking about it in an interview

You can say something like:

> "To keep my ISO 8583 skills sharp, I built a small issuer host and terminal with jPOS. It handles sign-on, 0100 and 0200, reversals with DE 90 and DE 95, duplicate detection, PIN tries, and the timeout-then-reversal flow, and I automated 15 scenarios with JUnit over TCP."

Then be ready to explain any field, response code or scenario in this README.

## Project layout

```
src/main/java/practice/iso/
  IssuerHost.java      TCP server: receive, decide, respond
  AuthEngine.java      The issuer's decision logic (start reading here)
  Card.java            One test card
  CardStore.java       The six test cards
  PinBlock.java        ISO format 0 PIN block
  Terminal.java        Interactive menu
  TerminalClient.java  Connection, response matching, late-response handling
  MessageFactory.java  Builds 0800, 0100, 0200 and 0420 messages
  MessagePrinter.java  Prints MTI, bitmap and every data element
  Iso.java             Packager, channel, response code names
src/test/java/practice/iso/
  IssuerScenariosTest.java   15 end-to-end scenarios
```
