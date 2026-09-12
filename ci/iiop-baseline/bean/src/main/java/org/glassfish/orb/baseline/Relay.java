package org.glassfish.orb.baseline;

import jakarta.ejb.Remote;

/**
 * A bean on one server that calls a bean on another.
 * <p>
 * This is the shape two application servers make when they talk to each
 * other, and the reason the transport exists: the call goes out over HTTP,
 * inside whatever transaction the original caller brought, and the far bean
 * has to end up in that same transaction.
 */
@Remote
public interface Relay {

    /**
     * Calls the far bean and reports what happened.
     *
     * <p>Answers rather than throws, and that is deliberate. An exception from
     * two servers away arrives at the client as whatever survived being
     * serialised twice - in practice "detail could not be decoded", which
     * names nothing. A string crosses intact and says what went wrong.
     *
     * @return "here|there" with both transaction keys, or a line beginning
     *         with ERROR describing what stopped the second hop
     */
    String bothTransactions();

    /** @return "ok", or a line beginning with ERROR */
    String markRollbackOnlyRemotely();

    /** @return the far server's transaction key, "none", or an ERROR line */
    String remoteTransaction();
}
