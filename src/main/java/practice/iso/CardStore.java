package practice.iso;

import static practice.iso.Card.Status.ACTIVE;
import static practice.iso.Card.Status.BLOCKED;
import static practice.iso.Card.Status.LOST;
import static practice.iso.Card.Status.STOLEN;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The issuer's card database. Every host starts with the same six test cards. */
final class CardStore {

    private final Map<String, Card> byPan = new LinkedHashMap<>();

    static CardStore withTestCards() {
        CardStore store = new CardStore();
        store.add(new Card("Visa, active", "4111111111111111", "2812", "1234", ACTIVE, 25_000_00, 10_000_00));
        store.add(new Card("Visa, low balance", "4000000000000002", "2812", "4321", ACTIVE, 500_00, 10_000_00));
        store.add(new Card("Mastercard, expired", "5555555555554444", "2401", "1111", ACTIVE, 10_000_00, 10_000_00));
        store.add(new Card("Mastercard, blocked", "5105105105105100", "2812", "2222", BLOCKED, 10_000_00, 10_000_00));
        store.add(new Card("RuPay, reported lost", "6521000000000017", "2812", "3333", LOST, 10_000_00, 10_000_00));
        store.add(new Card("RuPay, reported stolen", "6521000000000025", "2812", "4444", STOLEN, 10_000_00, 10_000_00));
        return store;
    }

    private void add(Card card) {
        byPan.put(card.pan, card);
    }

    Card find(String pan) {
        return byPan.get(pan);
    }

    List<Card> all() {
        return new ArrayList<>(byPan.values());
    }

    /** A printable table of the cards, for the host banner and the terminal's card list. */
    static String sheet(List<Card> cards) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  #  %-24s %-19s %-6s %-4s %-8s %14s %14s%n",
                "Card", "PAN", "Expiry", "PIN", "Status", "Balance INR", "Txn limit INR"));
        int i = 1;
        for (Card c : cards) {
            sb.append(String.format("  %d  %-24s %-19s %-6s %-4s %-8s %14s %14s%n",
                    i++, c.label, c.pan, c.expiry, c.pin, c.status, Iso.inr(c.available()), Iso.inr(c.perTxnLimit)));
        }
        return sb.toString();
    }
}
