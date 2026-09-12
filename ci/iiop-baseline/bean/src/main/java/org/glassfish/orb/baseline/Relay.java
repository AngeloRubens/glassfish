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
     * Calls the far bean and reports both transactions.
     *
     * @return "here|there" - this server's transaction key and the far
     *         server's, so the caller can see whether they are the same one
     */
    String bothTransactions();

    /** Has the far bean mark the transaction for rollback. */
    void markRollbackOnlyRemotely();

    /** @return the far server's transaction key alone, or null */
    String remoteTransaction();
}
