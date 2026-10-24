package practice.iso;

import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;

import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.channel.NACChannel;

/**
 * The issuer host: listens on a TCP port, receives ISO 8583 requests, asks the
 * AuthEngine for a decision and sends the response back.
 *
 * Each terminal connection must sign on (0800, DE 70 = 001) before it can send
 * transactions; until then the host answers 91.
 *
 * Run: run-host.cmd [port]   (default port 8583)
 */
public final class IssuerHost implements AutoCloseable {

    private final AuthEngine engine;
    private final boolean verbose;
    private ServerSocket server;
    private volatile boolean running;

    public IssuerHost(boolean verbose) {
        this(new AuthEngine(CardStore.withTestCards()), verbose);
    }

    IssuerHost(AuthEngine engine, boolean verbose) {
        this.engine = engine;
        this.verbose = verbose;
    }

    AuthEngine engine() {
        return engine;
    }

    /** Starts listening and returns the port in use (pass 0 to pick a free port). */
    public int start(int port) throws IOException {
        server = new ServerSocket(port);
        running = true;
        Thread accept = new Thread(this::acceptLoop, "issuer-accept");
        accept.setDaemon(true);
        accept.start();
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            NACChannel channel;
            try {
                channel = Iso.serverChannel();
                channel.accept(server);
            } catch (IOException e) {
                if (running) {
                    log("Could not accept a connection: " + e.getMessage());
                }
                continue;
            }
            log("\nTerminal connected from " + channel.getSocket().getRemoteSocketAddress());
            Thread session = new Thread(() -> serve(channel), "issuer-session");
            session.setDaemon(true);
            session.start();
        }
    }

    private void serve(NACChannel channel) {
        boolean signedOn = false;
        try {
            while (running) {
                ISOMsg req = channel.receive();
                if (verbose) {
                    MessagePrinter.print("<<< RECEIVED", req, false);
                }
                boolean network = req.getMTI().charAt(1) == '8';
                AuthEngine.Outcome out = !signedOn && !network
                        ? engine.reject(req, "91", "this terminal has not signed on (send 0800 with DE 70 = 001 first)")
                        : engine.handle(req);
                if (out == null) {
                    log("    No response sent: MTI " + req.getMTI() + " is not supported by this host");
                    continue;
                }
                String code = out.response().getString(39);
                if (network && "00".equals(code)) {
                    signedOn = "001".equals(req.getString(70)) || (signedOn && !"002".equals(req.getString(70)));
                }
                log("    Issuer decision: " + code + " (" + Iso.describeCode(code) + ") - " + out.reason());
                if (out.delayMs() > 0) {
                    Thread.sleep(out.delayMs());
                }
                channel.send(out.response());
                if (verbose) {
                    MessagePrinter.print(">>> SENT", out.response(), false);
                }
            }
        } catch (EOFException e) {
            log("\nTerminal disconnected");
        } catch (IOException | ISOException e) {
            if (running) {
                log("\nSession ended: " + e.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                channel.disconnect();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    private void log(String line) {
        if (verbose) {
            System.out.println(line);
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        if (server != null) {
            server.close();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Iso.DEFAULT_PORT;
        IssuerHost host = new IssuerHost(true);
        int actual = host.start(port);
        System.out.println("=== ISO 8583 practice issuer host ===");
        System.out.println("Test cards:");
        System.out.print(CardStore.sheet(host.engine().cards().all()));
        System.out.println("A purchase or withdrawal of exactly INR 777.00 is answered 5 seconds late (timeout test).");
        System.out.println("Listening on port " + actual + ". Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}
