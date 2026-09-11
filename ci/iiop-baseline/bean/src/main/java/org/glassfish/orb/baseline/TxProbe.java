package org.glassfish.orb.baseline;

import jakarta.ejb.Remote;

/**
 * Reports the transaction it is running under.
 * <p>
 * Nothing else here can tell whether a transaction actually crossed the wire.
 * A call that succeeds proves the bean ran; only the transaction's own key
 * proves it ran inside the caller's transaction rather than one of its own.
 */
@Remote
public interface TxProbe {

    /**
     * @return the current transaction's key as a string, or null when this
     *         call is not running in a transaction at all
     */
    String transactionKey();

    /** Marks the current transaction for rollback from inside the bean. */
    void markRollbackOnly();
}
