/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.orb.http.client;


import javax.transaction.xa.Xid;

/**
 * The transaction currently associated with the calling thread, if any.
 *
 * <p>Every invocation consults this to decide what to write into the
 * transaction prefix of its body. Thread association is not a design choice
 * here so much as an inherited one: {@code UserTransaction} and
 * {@code XAResource.start} are both defined in terms of the current thread,
 * so anything driving them has to keep the same shape.
 *
 * <p>The timeout travels with it. A branch imported by the server under the
 * server's own default rather than the coordinator's remaining time can
 * outlive the transaction it belongs to, which turns a clean rollback into a
 * heuristic outcome someone has to resolve by hand.
 */
public final class ClientTransactionContext {

    private record Association(Xid xid, long timeoutSeconds, java.util.concurrent.atomic.AtomicBoolean rollbackOnly) {

        Association(Xid xid, long timeoutSeconds) {
            this(xid, timeoutSeconds, new java.util.concurrent.atomic.AtomicBoolean());
        }
    }

    private static final ThreadLocal<Association> CURRENT = new ThreadLocal<>();

    private ClientTransactionContext() {
    }

    /** @return the transaction on this thread, or null */
    public static Xid current() {
        Association association = CURRENT.get();
        return association == null ? null : association.xid();
    }

    /** @return the remaining timeout in seconds, or 0 if unknown */
    public static long currentTimeoutSeconds() {
        Association association = CURRENT.get();
        return association == null ? 0 : association.timeoutSeconds();
    }

    /**
     * Records that this transaction can no longer be committed.
     * <p>
     * Set either by the caller, or by a reply saying a bean on the server has
     * decided it. The two are the same fact and are kept in the same place, so
     * that whoever commits sees it however it arrived.
     */
    public static void markRollbackOnly() {
        Association association = CURRENT.get();
        if (association != null) {
            association.rollbackOnly().set(true);
        }
    }

    /** @return whether this transaction has been marked, by either side */
    public static boolean isRollbackOnly() {
        Association association = CURRENT.get();
        return association != null && association.rollbackOnly().get();
    }

    public static void associate(Xid xid, long timeoutSeconds) {
        if (xid == null) {
            disassociate();
        } else {
            CURRENT.set(new Association(xid, timeoutSeconds));
        }
    }

    /**
     * Clears the association. Always call this from a finally block: a thread
     * returned to a pool still carrying a transaction would enlist whatever
     * work ran next into a transaction that has already been decided.
     */
    public static void disassociate() {
        CURRENT.remove();
    }
}
