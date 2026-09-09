package org.glassfish.orb.baseline;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * The value types whose treatment separates a remote view from an
 * approximation of one. Every case here is something a JSON binding fails.
 */
public final class Values {

    private Values() {
    }

    /** Holds a reference to another, so a graph can be made cyclic. */
    public static final class Node implements Serializable {

        private static final long serialVersionUID = 1L;

        public final String name;
        public Node next;

        public Node(String name) {
            this.name = name;
        }
    }

    /** Two fields pointing at one object: identity, not merely equality. */
    public static final class Shared implements Serializable {

        private static final long serialVersionUID = 1L;

        public final Node left;
        public final Node right;

        public Shared(Node left, Node right) {
            this.left = left;
            this.right = right;
        }
    }

    public static final class WithTransient implements Serializable {

        private static final long serialVersionUID = 1L;

        public final String kept;
        public transient String dropped;

        public WithTransient(String kept, String dropped) {
            this.kept = kept;
            this.dropped = dropped;
        }
    }

    /** An application exception carrying business state, not just a message. */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String reasonCode;
        private final List<String> offending = new ArrayList<>();

        public Refused(String message, String reasonCode, Throwable cause) {
            super(message, cause);
            this.reasonCode = reasonCode;
            this.offending.add("first");
            this.offending.add("second");
        }

        public String reasonCode() {
            return reasonCode;
        }

        public List<String> offending() {
            return offending;
        }
    }
}
