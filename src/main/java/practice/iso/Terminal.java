package practice.iso;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.util.List;
import java.util.Scanner;

import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;

/**
 * Interactive terminal / acquirer. Pick a transaction from the menu, and it
 * prints every message it sends and receives, field by field.
 *
 * Run: run-terminal.cmd [host] [port]   (default localhost 8583)
 */
public final class Terminal {

    private static final long RESPONSE_TIMEOUT_MS = 3_000;
    private static final long REVERSAL_TIMEOUT_MS = 8_000;

    private final TerminalClient client;
    private final MessageFactory factory = new MessageFactory();
    private final Scanner in = new Scanner(System.in);
    private final List<Card> cards = CardStore.withTestCards().all();

    private ISOMsg lastRequest;
    private ISOMsg lastApproved;

    private Terminal(TerminalClient client) {
        this.client = client;
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : Iso.DEFAULT_PORT;
        TerminalClient client = new TerminalClient(host, port, true);
        try {
            client.connect();
        } catch (ConnectException e) {
            System.out.println("Could not connect to " + host + ":" + port + ". Start the issuer host first (run-host.cmd).");
            return;
        }
        System.out.println("Connected to the issuer host at " + host + ":" + port + ".");
        new Terminal(client).run();
        client.close();
    }

    private void run() throws Exception {
        System.out.println("Start with 1 (sign-on). Until then the host answers 91 to every transaction.");
        while (true) {
            printMenu();
            String choice = ask("Choose", "").toUpperCase();
            try {
                switch (choice) {
                    case "1" -> send(factory.network("001"));
                    case "2" -> send(factory.network("301"));
                    case "3" -> send(factory.network("002"));
                    case "4" -> purchase();
                    case "5" -> cashWithdrawal();
                    case "6" -> balanceInquiry();
                    case "7" -> reverseLast(false);
                    case "8" -> reverseLast(true);
                    case "9" -> resendLast();
                    case "10" -> reverseMissing();
                    case "C" -> System.out.print("\n" + CardStore.sheet(cards));
                    case "0" -> {
                        return;
                    }
                    default -> System.out.println("Pick one of the options in the menu.");
                }
            } catch (ISOException e) {
                System.out.println("Could not build or send the message: " + e.getMessage());
            } catch (IOException e) {
                System.out.println("Connection problem: " + e.getMessage() + ". Restart the terminal.");
                return;
            }
        }
    }

    private void printMenu() {
        System.out.println();
        System.out.println("------------------------------------------------------------------");
        System.out.println(" 1  Sign-on                        0800  DE 70 = 001");
        System.out.println(" 2  Echo test                      0800  DE 70 = 301");
        System.out.println(" 3  Sign-off                       0800  DE 70 = 002");
        System.out.println(" 4  Purchase                       0100  DE 3 = 000000");
        System.out.println(" 5  Cash withdrawal (needs PIN)    0200  DE 3 = 010000");
        System.out.println(" 6  Balance inquiry (needs PIN)    0200  DE 3 = 310000");
        System.out.println(" 7  Full reversal of the last approved transaction      0420");
        System.out.println(" 8  Partial reversal of the last approved transaction   0420 + DE 95");
        System.out.println(" 9  Resend the last request unchanged (duplicate test)");
        System.out.println("10  Reverse a transaction that never happened (expect 25)");
        System.out.println(" C  Show the test cards");
        System.out.println(" 0  Exit");
        System.out.println("Tip: a purchase or withdrawal of exactly INR 777.00 is answered late (timeout test).");
    }

    private void purchase() throws Exception {
        Card card = askCard();
        String pan = card.pan;
        String expiry = ask("Expiry YYMM", card.expiry);
        long amount = askAmount();
        String pin = ask("PIN (leave blank for no PIN)", "");
        sendFinancial(factory.purchase(pan, expiry, amount, pin.isEmpty() ? null : pin));
    }

    private void cashWithdrawal() throws Exception {
        Card card = askCard();
        String expiry = ask("Expiry YYMM", card.expiry);
        long amount = askAmount();
        String pin = ask("PIN", card.pin);
        sendFinancial(factory.cashWithdrawal(card.pan, expiry, amount, pin.isEmpty() ? null : pin));
    }

    private void balanceInquiry() throws Exception {
        Card card = askCard();
        String expiry = ask("Expiry YYMM", card.expiry);
        String pin = ask("PIN", card.pin);
        ISOMsg resp = send(factory.balanceInquiry(card.pan, expiry, pin.isEmpty() ? null : pin));
        if (resp != null && resp.hasField(54)) {
            System.out.println("    DE 54 decoded: " + describeBalances(resp.getString(54)));
        }
    }

    /** Sends a 0100/0200. If no response arrives in time, sends a 0420 reversal with DE 39 = 68. */
    private void sendFinancial(ISOMsg req) throws Exception {
        ISOMsg resp = send(req);
        if (resp == null) {
            System.out.println("    The terminal does not know whether the issuer approved, so it must reverse.");
            System.out.println("    Sending a 0420 reversal advice with DE 39 = 68 (response received too late).");
            client.send(factory.reversal(req, null, "68"), REVERSAL_TIMEOUT_MS);
        } else if ("00".equals(resp.getString(39))) {
            lastApproved = req;
        }
    }

    private void reverseLast(boolean partial) throws Exception {
        if (lastApproved == null) {
            System.out.println("There is no approved purchase or withdrawal to reverse yet.");
            return;
        }
        long original = Long.parseLong(lastApproved.getString(4));
        Long keep = null;
        if (partial) {
            System.out.println("Original amount: INR " + Iso.inr(original));
            keep = askAmount("Amount that should stay charged (INR)");
        }
        send(factory.reversal(lastApproved, keep, "17"));
    }

    private void resendLast() throws Exception {
        if (lastRequest == null) {
            System.out.println("Nothing has been sent yet.");
            return;
        }
        System.out.println("Resending STAN " + lastRequest.getString(11) + " with the same DE 7. A careful issuer answers 94.");
        send(lastRequest);
    }

    private void reverseMissing() throws Exception {
        ISOMsg ghost = factory.purchase(cards.get(0).pan, cards.get(0).expiry, 100_00, null);
        ghost.set(11, "999999");
        ghost.set(7, "0101000000");
        System.out.println("Building a reversal for STAN 999999, DE 7 0101000000, which the issuer never approved.");
        send(factory.reversal(ghost, null, "17"));
    }

    private ISOMsg send(ISOMsg req) throws Exception {
        lastRequest = req;
        return client.send(req, req.getMTI().startsWith("04") ? REVERSAL_TIMEOUT_MS : RESPONSE_TIMEOUT_MS);
    }

    private static String describeBalances(String de54) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 20 <= de54.length(); i += 20) {
            String entry = de54.substring(i, i + 20);
            String type = entry.substring(2, 4).equals("01") ? "ledger" : entry.substring(2, 4).equals("02") ? "available" : "type " + entry.substring(2, 4);
            long amount = Long.parseLong(entry.substring(8));
            sb.append(type).append(" INR ").append(entry.charAt(7) == 'D' ? "-" : "").append(Iso.inr(amount)).append("   ");
        }
        return sb.toString().trim();
    }

    private Card askCard() {
        System.out.print("\n" + CardStore.sheet(cards));
        String value = ask("Card number 1-" + cards.size() + ", or type any PAN", "1");
        if (value.matches("\\d") && Integer.parseInt(value) >= 1 && Integer.parseInt(value) <= cards.size()) {
            return cards.get(Integer.parseInt(value) - 1);
        }
        return new Card("Typed PAN", value, "2812", "0000", Card.Status.ACTIVE, 0, 0);
    }

    private long askAmount() {
        return askAmount("Amount in INR (e.g. 1500 or 249.50)");
    }

    private long askAmount(String prompt) {
        while (true) {
            String value = ask(prompt, "");
            try {
                return new BigDecimal(value).movePointRight(2).longValueExact();
            } catch (NumberFormatException | ArithmeticException e) {
                System.out.println("Enter an amount like 1500 or 249.50.");
            }
        }
    }

    private String ask(String prompt, String defaultValue) {
        System.out.print(prompt + (defaultValue.isEmpty() ? "" : " [" + defaultValue + "]") + ": ");
        if (!in.hasNextLine()) {
            return "0";
        }
        String line = in.nextLine().trim();
        return line.isEmpty() ? defaultValue : line;
    }
}
