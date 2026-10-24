package practice.iso;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.channel.NACChannel;

/**
 * The terminal's connection to the issuer host.
 *
 * A background thread reads every message that arrives. send() then waits
 * for the response that matches the request (response MTI and the same STAN)
 * and discards anything else, such as a response that arrives after the
 * terminal has already timed out and reversed.
 */
final class TerminalClient implements AutoCloseable {

    private final NACChannel channel;
    private final BlockingQueue<ISOMsg> inbox = new LinkedBlockingQueue<>();
    private final boolean verbose;

    TerminalClient(String host, int port, boolean verbose) {
        this.channel = Iso.clientChannel(host, port);
        this.verbose = verbose;
    }

    void connect() throws IOException {
        channel.connect();
        Thread reader = new Thread(this::readLoop, "terminal-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        try {
            while (true) {
                inbox.put(channel.receive());
            }
        } catch (IOException | ISOException e) {
            // the connection closed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sends a request and waits up to timeoutMs for its response. Returns null on timeout. */
    ISOMsg send(ISOMsg req, long timeoutMs) throws IOException, ISOException, InterruptedException {
        if (verbose) {
            MessagePrinter.print(">>> SENT", req, true);
        }
        channel.send(req);
        String wantMti = Iso.responseMti(req.getMTI());
        String wantStan = req.getString(11);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            ISOMsg m = left > 0 ? inbox.poll(left, TimeUnit.MILLISECONDS) : null;
            if (m == null) {
                if (verbose) {
                    System.out.println("\n!!! No response within " + timeoutMs + " ms");
                }
                return null;
            }
            if (wantMti.equals(m.getMTI()) && wantStan.equals(m.getString(11))) {
                if (verbose) {
                    MessagePrinter.print("<<< RECEIVED", m, true);
                }
                return m;
            }
            if (verbose) {
                MessagePrinter.print("<<< LATE OR UNMATCHED response, discarded (expected " + wantMti
                        + " for STAN " + wantStan + ")", m, false);
            }
        }
    }

    @Override
    public void close() throws IOException {
        channel.disconnect();
    }
}
