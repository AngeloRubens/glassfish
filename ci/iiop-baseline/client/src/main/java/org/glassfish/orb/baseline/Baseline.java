package org.glassfish.orb.baseline;

import java.util.ArrayList;
import java.util.List;

import javax.naming.Context;

/**
 * What a remote view carries, asserted once and run over either transport.
 *
 * <p>Being one class is the entire point. Two similar test classes, one per
 * transport, would drift: a case tightened on one side and not the other, and
 * the comparison quietly stops being a comparison. Here the assertions cannot
 * differ between IIOP and HTTP, because there is only one copy of them, and
 * the only thing the two callers supply is a {@link Context}.
 */
public final class Baseline {

    private static final String APP = "orb-baseline-bean";

    private Baseline() {
    }

    /**
     * @param context a JNDI context reaching the server over some transport
     * @param transport what to call it in the output
     * @return the number of cases that failed
     */
    public static int run(Context context, String transport) throws Exception {
        List<String> failures = new ArrayList<>();
        System.out.println("== " + transport + " ==");

        Greeter greeter = (Greeter) context.lookup(
                "java:global/" + APP + "/GreeterBean!" + Greeter.class.getName());

        check(failures, "a plain invocation", () ->
                expect("Ada Ada Ada", greeter.greet("Ada", 3)));

        check(failures, "a cyclic graph closes on the same object", () -> {
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

        check(failures, "a shared reference keeps its identity", () -> {
            Values.Node shared = new Values.Node("shared");
            Values.Shared returned = greeter.echoShared(new Values.Shared(shared, shared));
            if (returned.left != returned.right) {
                throw new AssertionError("one object came back as two");
            }
        });

        check(failures, "transient fields are not transmitted", () -> {
            Values.WithTransient returned =
                    greeter.echoTransient(new Values.WithTransient("kept", "dropped"));
            expect("kept", returned.kept);
            if (returned.dropped != null) {
                throw new AssertionError("a transient field crossed the wire");
            }
        });

        check(failures, "an application exception arrives with its state", () -> {
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

        check(failures, "an unannotated unchecked exception becomes EJBException", () -> {
            try {
                greeter.explode();
                throw new AssertionError("no exception was thrown");
            } catch (Values.Exploded original) {
                throw new AssertionError("a system exception was let through unchanged;"
                        + " it is supposed to be wrapped and the instance discarded");
            } catch (jakarta.ejb.NoSuchEJBException missing) {
                // NoSuchEJBException extends EJBException, so catching the
                // parent alone let this case pass on a run where every other
                // case failed with exactly this - a test that reports success
                // when nothing works is worse than no test.
                throw new AssertionError("the bean was not reachable at all: " + missing);
            } catch (jakarta.ejb.EJBException wrapped) {
                // The contract: unchecked and unannotated is a system failure,
                // and the caller sees EJBException rather than what was thrown.
            }
        });

        check(failures, "a large payload round trips", () -> {
            String payload = "x".repeat(2 * 1024 * 1024);
            expect(payload.length(), greeter.echoLarge(payload).length());
        });

        check(failures, "a stateful conversation keeps its state", () -> {
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

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println(transport + ": every case above is what a remote view carries.");
        } else {
            System.out.println(failures.size() + " case(s) failed over " + transport + ':');
            failures.forEach(f -> System.out.println("  " + f));
        }
        return failures.size();
    }

    private interface Case {
        void run() throws Exception;
    }

    private static void check(List<String> failures, String description, Case body) {
        try {
            body.run();
            System.out.println("  ok    " + description);
        } catch (Throwable t) {
            System.out.println("  FAIL  " + description + " -- " + t);
            failures.add(description + ": " + t);
        }
    }

    private static void expect(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
