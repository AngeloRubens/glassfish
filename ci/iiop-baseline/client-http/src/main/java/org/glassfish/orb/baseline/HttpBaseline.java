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

        failures += xaBranch(probe, environment);

        System.out.println();
        return failures;
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
