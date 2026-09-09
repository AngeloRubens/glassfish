package org.glassfish.orb.baseline;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import javax.naming.Context;
import javax.naming.InitialContext;

/**
 * Invokes the test bean over RMI-IIOP against a running GlassFish, and records
 * what a remote view actually carries.
 *
 * <p>The ORB-over-HTTP work claims to reproduce this behaviour. A claim of
 * equivalence is worth exactly as much as the description of the thing being
 * equalled, and until now that description was asserted from reading the
 * specification rather than measured against a server. These are the same
 * cases {@code ObjectGraphFidelityTest} checks over HTTP, run over IIOP.
 *
 * <p>No JNDI properties are set on purpose. With {@code gf-client.jar} on the
 * classpath, a bare {@code new InitialContext()} reaches the ORB on
 * localhost:3700 - which is exactly the client shape the migration proposes to
 * change by two lines.
 *
 * <p>Exits non-zero if any case fails, so the CI step needs no parsing.
 */
public final class IiopBaseline {

    private static final String APP = "orb-baseline-bean";
    private static final List<String> FAILURES = new ArrayList<>();

    private IiopBaseline() {
    }

    public static void main(String[] args) throws Exception {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY,
                "com.sun.enterprise.naming.SerialInitContextFactory");
        environment.put("org.omg.CORBA.ORBInitialHost",
                System.getProperty("orb.host", "localhost"));
        environment.put("org.omg.CORBA.ORBInitialPort",
                System.getProperty("orb.port", "3700"));

        InitialContext context = new InitialContext(environment);

        Greeter greeter = (Greeter) context.lookup(
                "java:global/" + APP + "/GreeterBean!" + Greeter.class.getName());

        check("a plain invocation", () ->
                expect("Ada Ada Ada", greeter.greet("Ada", 3)));

        check("a cyclic graph closes on the same object", () -> {
            Values.Node first = new Values.Node("first");
            Values.Node second = new Values.Node("second");
            first.next = second;
            second.next = first;

            Values.Node returned = greeter.echoNode(first);
            expect("first", returned.name);
            expect("second", returned.next.name);
            if (returned != returned.next.next) {
                throw new AssertionError("the cycle did not close on the same object");
            }
        });

        check("a shared reference keeps its identity", () -> {
            Values.Node shared = new Values.Node("shared");
            Values.Shared returned = greeter.echoShared(new Values.Shared(shared, shared));
            if (returned.left != returned.right) {
                throw new AssertionError("one object came back as two");
            }
        });

        check("transient fields are not transmitted", () -> {
            Values.WithTransient returned =
                    greeter.echoTransient(new Values.WithTransient("kept", "dropped"));
            expect("kept", returned.kept);
            if (returned.dropped != null) {
                throw new AssertionError("a transient field crossed the wire");
            }
        });

        check("an application exception arrives with its state", () -> {
            try {
                greeter.refuse();
                throw new AssertionError("no exception was thrown");
            } catch (Values.Refused refused) {
                expect("not today", refused.getMessage());
                expect("E_CLOSED", refused.reasonCode());
                expect(List.of("first", "second"), refused.offending());
                if (!(refused.getCause() instanceof IllegalStateException)) {
                    throw new AssertionError("the cause chain did not survive: " + refused.getCause());
                }
                if (refused.getStackTrace().length == 0) {
                    throw new AssertionError("the server stack trace did not survive");
                }
            }
        });

        check("an unannotated unchecked exception becomes EJBException", () -> {
            try {
                greeter.explode();
                throw new AssertionError("no exception was thrown");
            } catch (Values.Exploded original) {
                throw new AssertionError("the container let a system exception through unchanged;"
                        + " it is supposed to wrap it and discard the instance");
            } catch (jakarta.ejb.EJBException wrapped) {
                // This is the contract: unchecked and unannotated means system
                // failure, and the caller sees EJBException rather than the
                // type the bean threw.
            }
        });

        check("a large payload round trips", () -> {
            String payload = "x".repeat(2 * 1024 * 1024);
            expect(payload.length(), greeter.echoLarge(payload).length());
        });

        check("a stateful conversation keeps its state", () -> {
            Counter counter = (Counter) context.lookup(
                    "java:global/" + APP + "/CounterBean!" + Counter.class.getName());
            Counter other = (Counter) context.lookup(
                    "java:global/" + APP + "/CounterBean!" + Counter.class.getName());

            expect(1, counter.increment());
            expect(2, counter.increment());
            expect(2, counter.value());
            expect(1, other.increment());
            expect(2, counter.value());
        });

        context.close();

        System.out.println();
        if (FAILURES.isEmpty()) {
            System.out.println("IIOP baseline established: every case above is what a remote "
                    + "view carries today, and what the HTTP transport has to reproduce.");
            return;
        }
        System.out.println(FAILURES.size() + " case(s) failed:");
        FAILURES.forEach(f -> System.out.println("  " + f));
        System.exit(1);
    }

    private interface Case {
        void run() throws Exception;
    }

    private static void check(String description, Case body) {
        try {
            body.run();
            System.out.println("  ok    " + description);
        } catch (Throwable t) {
            System.out.println("  FAIL  " + description + " -- " + t);
            FAILURES.add(description + ": " + t);
        }
    }

    private static void expect(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
