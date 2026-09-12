package org.glassfish.orb.baseline;

import jakarta.transaction.RollbackException;
import jakarta.transaction.UserTransaction;

import java.net.URI;
import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.InitialContext;

import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.glassfish.orb.http.client.ClientConfiguration;
import org.glassfish.orb.http.client.HttpUserTransaction;
import org.glassfish.orb.http.client.HttpXAResource;
import org.glassfish.orb.http.client.JdkHttpTransport;
import org.glassfish.orb.http.protocol.Xids;

/**
 * The same baseline over HTTP, and the whole argument in one file.
 *
 * <p>Compare this with {@link IiopBaseline}. The environment differs by two
 * entries; the assertions are not merely equivalent but literally the same
 * code, in {@link Baseline}. If this passes, the claim that an existing client
 * migrates by changing the factory and the URL is not an argument from the
 * shape of the code - it is a thing that ran.
 */
public final class HttpBaseline {

    private HttpBaseline() {
    }

    public static void main(String[] args) throws Exception {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY,
                "org.glassfish.orb.http.client.HttpInitialContextFactory");
        environment.put(Context.PROVIDER_URL,
                System.getProperty("endpoint", "http://localhost:8080/glassfish-services"));

        // When a codec is named, require it. Without this the client would
        // fall back to Java serialization if the server could not read the
        // codec, and the run would pass while proving nothing.
        String codec = System.getProperty("codec");
        if (codec != null && !codec.isBlank()) {
            environment.put("org.glassfish.orb.http.codec", codec);
            System.out.println("requiring codec: " + codec);
        }

        InitialContext context = new InitialContext(environment);
        try {
            String label = codec == null || codec.isBlank() ? "HTTP" : "HTTP/" + codec;
            int failures = Baseline.run(context, label);
            failures += transactions(context, environment);
            System.exit(failures == 0 ? 0 : 1);
        } finally {
            context.close();
        }
    }

    /**
     * Distributed transactions, which only this transport can be asked about.
     *
     * <p>Not in {@link Baseline}, deliberately. The shared assertions are the
     * ones both transports answer identically; a client-driven UserTransaction
     * over HTTP is not one of those, and putting it there would make the two
     * runs look alike by making one of them do less.
     *
     * <p>What is checked is the only thing that distinguishes a transaction
     * that travelled from one the server started by itself: the key the bean
     * reports. Two calls inside one client transaction must report the same
     * key, and a call outside must report none.
     */
    private static int transactions(InitialContext context, Hashtable<String, String> environment)
            throws Exception {
        System.out.println();
        System.out.println("== transactions ==");
        int failures = 0;

        TxProbe probe = (TxProbe) context.lookup(
                "java:global/orb-baseline-bean/TxProbeBean!" + TxProbe.class.getName());
        UserTransaction transaction = userTransaction(environment);

        failures += check("a call outside a transaction is in none",
                () -> expectNull(probe.transactionKey()));

        failures += check("two calls in one transaction share it", () -> {
            transaction.begin();
            try {
                String first = probe.transactionKey();
                String second = probe.transactionKey();
                expectNotNull(first, "the transaction did not reach the bean");
                if (!first.equals(second)) {
                    throw new IllegalStateException("two keys in one transaction: " + first + " and " + second);
                }
            } finally {
                transaction.commit();
            }
        });

        failures += check("the transaction ends when it is committed",
                () -> expectNull(probe.transactionKey()));

        failures += check("a rollback is honoured", () -> {
            transaction.begin();
            expectNotNull(probe.transactionKey(), "the transaction did not reach the bean");
            transaction.rollback();
            expectNull(probe.transactionKey());
        });

        failures += check("a bean marking rollback-only is refused a commit", () -> {
            transaction.begin();
            probe.markRollbackOnly();

            // Reported rather than assumed. Two outcomes are defensible - the
            // commit is refused, or the container has already rolled the
            // transaction back and there is nothing left to refuse - and only
            // one is a fault: the work being committed. Saying which happened
            // is the difference between a diagnosis and another guess.
            String outcome;
            try {
                transaction.commit();
                outcome = "committed, status after = " + transaction.getStatus();
            } catch (RollbackException e) {
                outcome = "refused";
            } catch (Exception e) {
                outcome = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            System.out.println("        rollback-only commit -> " + outcome);

            if (!"refused".equals(outcome)) {
                throw new IllegalStateException("the commit was not refused: " + outcome);
            }
        });

        failures += attributes(probe, transaction);
        failures += acrossTwoServers(context, transaction);
        failures += xaBranch(probe, environment);

        System.out.println();
        return failures;
    }

    /**
     * A client calls a bean here, and that bean calls a bean over there.
     *
     * <p>This is the case two application servers make when they talk to each
     * other, and the whole point of the transport: the second hop is HTTP as
     * well, made by a bean that names a factory and a URL and nothing else.
     *
     * <p>What is not asserted is that both servers report the same transaction
     * key. They cannot: a key is a local object in its own JVM, and two of them
     * would differ for a single global transaction as surely as for two. The
     * evidence that this is one unit of work is elsewhere - a rollback decided
     * on the far server reaching the client's commit, two hops back.
     */
    private static int acrossTwoServers(InitialContext context, UserTransaction transaction) {
        System.out.println();
        System.out.println("== across two servers ==");
        int failures = 0;

        Relay relay;
        try {
            relay = (Relay) context.lookup(
                    "java:global/orb-baseline-relay/RelayBean!" + Relay.class.getName());
        } catch (Exception e) {
            System.out.println("  SKIP  the relay is not deployed -- " + e);
            return 0;
        }

        failures += check("the caller's transaction reaches the second server", () -> {
            transaction.begin();
            try {
                String both = relay.bothTransactions();
                System.out.println("        relay said: " + both);
                if (both.startsWith("ERROR")) {
                    throw new IllegalStateException(both);
                }
                String[] hops = both.split("\\|", -1);
                if (hops.length != 2) {
                    throw new IllegalStateException("unreadable answer: " + both);
                }
                if ("none".equals(hops[0])) {
                    throw new IllegalStateException("the first server was not in a transaction");
                }
                if (hops[1] == null || hops[1].isEmpty() || "null".equals(hops[1])) {
                    throw new IllegalStateException(
                            "the second server ran outside a transaction: " + both);
                }
                System.out.println("        first=" + hops[0] + " second=" + hops[1]);
            } finally {
                transaction.commit();
            }
        });

        failures += check("a rollback decided on the second server reaches the client", () -> {
            transaction.begin();
            boolean refused = false;
            try {
                String marked = relay.markRollbackOnlyRemotely();
                if (marked.startsWith("ERROR")) {
                    throw new IllegalStateException(marked);
                }
                transaction.commit();
            } catch (RollbackException e) {
                refused = true;
            }
            if (!refused) {
                // The decision was taken two servers away from the commit.
                // Accepting it would tell the client its work is durable when
                // the far server has already refused it.
                throw new IllegalStateException("a transaction the far server marked was committed");
            }
        });

        failures += check("with no transaction, the second hop runs without one either", () -> {
            String answer = relay.remoteTransaction();
            if (!"none".equals(answer)) {
                throw new IllegalStateException("expected no transaction, got: " + answer);
            }
        });

        System.out.println();
        return failures;
    }

    /**
     * What the container does with the caller's transaction, per attribute.
     *
     * <p>Every method asked here answers the same question - which transaction
     * am I in - so any difference between them is the attribute doing its
     * work. That is what makes this a test of the transport rather than of the
     * container: the container's behaviour is well defined, and what is under
     * test is whether a transaction that arrived over HTTP is a real one to it.
     */
    private static int attributes(TxProbe probe, UserTransaction transaction) throws Exception {
        System.out.println();
        System.out.println("== transaction attributes ==");
        int failures = 0;

        failures += check("inside a transaction, REQUIRED, MANDATORY and SUPPORTS all join it", () -> {
            transaction.begin();
            try {
                String required = probe.required();
                expectNotNull(required, "REQUIRED did not run in a transaction");
                same("MANDATORY", required, probe.mandatory());
                same("SUPPORTS", required, probe.supports());
            } finally {
                transaction.commit();
            }
        });

        failures += check("REQUIRES_NEW gets its own transaction, not the caller's", () -> {
            transaction.begin();
            try {
                String caller = probe.required();
                String fresh = probe.requiresNew();
                expectNotNull(fresh, "REQUIRES_NEW did not run in a transaction");
                if (caller.equals(fresh)) {
                    throw new IllegalStateException(
                            "REQUIRES_NEW ran in the caller's transaction: " + fresh);
                }
            } finally {
                transaction.commit();
            }
        });

        failures += check("NOT_SUPPORTED runs outside the caller's transaction", () -> {
            transaction.begin();
            try {
                expectNull(probe.notSupported());
                // And the caller's is still there afterwards: suspended, not ended.
                expectNotNull(probe.required(), "the caller's transaction did not come back");
            } finally {
                transaction.commit();
            }
        });

        failures += check("NEVER refuses to run inside a transaction", () -> {
            transaction.begin();
            try {
                probe.never();
                throw new IllegalStateException("NEVER ran with a transaction present");
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception expected) {
                // The specification says refuse; which exception carries that
                // is the container's business.
            } finally {
                transaction.rollback();
            }
        });

        failures += check("outside a transaction, MANDATORY refuses and the others cope", () -> {
            // REQUIRED starts one of its own.
            expectNotNull(probe.required(), "REQUIRED did not start a transaction");
            // SUPPORTS and NOT_SUPPORTED simply run without one.
            expectNull(probe.supports());
            expectNull(probe.notSupported());
            // NEVER is content, since there is nothing to refuse.
            expectNull(probe.never());
            try {
                probe.mandatory();
                throw new IllegalStateException("MANDATORY ran without a transaction");
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception expected) {
                // Refused, which is the whole meaning of the attribute.
            }
        });

        System.out.println();
        return failures;
    }

    private static void same(String what, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(what + " ran in " + actual
                    + " rather than the caller's " + expected);
        }
    }

    /**
     * The other family: this server as one branch of somebody else's
     * transaction.
     *
     * <p>The UserTransaction cases above have the client asking the server to
     * begin and end a transaction. Here the coordinator is outside and the
     * server is a resource it drives - start, do work, end, prepare, commit -
     * which is the case an application server makes when it calls another one.
     * The xid is invented here, as a coordinator invents it, and the server
     * meets it for the first time on the invocation.
     */
    private static int xaBranch(TxProbe probe, Hashtable<String, String> environment) {
        int failures = 0;
        URI base = URI.create(environment.get(Context.PROVIDER_URL));
        ClientConfiguration config = ClientConfiguration.builder(base).build();

        failures += check("a branch driven from outside commits in two phases", () -> {
            HttpXAResource resource = new HttpXAResource(config, new JdkHttpTransport(config));
            Xid xid = branch(1);

            resource.start(xid, XAResource.TMNOFLAGS);
            String key = probe.transactionKey();
            resource.end(xid, XAResource.TMSUCCESS);

            expectNotNull(key, "the coordinator's branch did not reach the bean");

            int vote = resource.prepare(xid);
            if (vote != XAResource.XA_OK && vote != XAResource.XA_RDONLY) {
                throw new IllegalStateException("unexpected vote: " + vote);
            }
            if (vote == XAResource.XA_OK) {
                resource.commit(xid, false);
            }
        });

        failures += check("a branch driven from outside can be rolled back", () -> {
            HttpXAResource resource = new HttpXAResource(config, new JdkHttpTransport(config));
            Xid xid = branch(2);

            resource.start(xid, XAResource.TMNOFLAGS);
            expectNotNull(probe.transactionKey(), "the coordinator's branch did not reach the bean");
            resource.end(xid, XAResource.TMSUCCESS);
            resource.rollback(xid);

            expectNull(probe.transactionKey());
        });

        failures += check("the server answers a recovery scan", () -> {
            // Not about finding anything - an idle server has nothing in
            // doubt. It is about the operation being reachable and answering,
            // which is what a coordinator needs after a crash.
            HttpXAResource resource = new HttpXAResource(config, new JdkHttpTransport(config));
            Xid[] inDoubt = resource.recover(XAResource.TMSTARTRSCAN);
            resource.recover(XAResource.TMENDRSCAN);
            if (inDoubt == null) {
                throw new IllegalStateException("recovery returned null rather than an empty scan");
            }
        });

        return failures;
    }

    private static Xid branch(int n) {
        return new Xids.SimpleXid(0x42415345,
                new byte[] { 'b', 'a', 's', 'e', (byte) n }, new byte[] { (byte) n });
    }

    private static UserTransaction userTransaction(Hashtable<String, String> environment) {
        URI base = URI.create(environment.get(Context.PROVIDER_URL));
        ClientConfiguration config = ClientConfiguration.builder(base).build();
        return new HttpUserTransaction(config, new JdkHttpTransport(config));
    }

    private interface Case {
        void run() throws Exception;
    }

    private static int check(String what, Case body) {
        try {
            body.run();
            System.out.println("  ok    " + what);
            return 0;
        } catch (Throwable t) {
            System.out.println("  FAIL  " + what + " -- " + t);
            return 1;
        }
    }

    private static void expectNull(String key) {
        if (key != null) {
            throw new IllegalStateException("expected no transaction, but the bean reported " + key);
        }
    }

    private static void expectNotNull(String key, String message) {
        if (key == null) {
            throw new IllegalStateException(message);
        }
    }
}
