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


package org.glassfish.orb.http.glassfish;

import com.sun.enterprise.transaction.api.JavaEETransactionManager;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.resource.spi.XATerminator;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.glassfish.orb.http.protocol.Xids;
import org.glassfish.orb.http.server.TransactionBridge;
import org.jvnet.hk2.annotations.Service;

/**
 * Runs the caller's transaction on this server.
 * <p>
 * There is almost nothing new here, and that is the point. GlassFish already
 * accepts transactions that begin somewhere else: a resource adapter inflows
 * work under a foreign {@code Xid}, the transaction manager recreates that
 * branch on the thread, and an {@link XATerminator} drives it to an outcome.
 * That contract was written for JCA, but nothing in it is about JCA - it is
 * about a transaction whose coordinator is not this JVM, which is exactly what
 * a remote client is.
 * <p>
 * So the HTTP transport does not gain a transaction implementation. It borrows
 * the one the server already trusts, and the work is translation.
 */
@Service
@Singleton
public class GlassFishTransactionBridge implements TransactionBridge {

    /**
     * Distinguishes branches this transport began from anything else the
     * server is coordinating. Chosen, not standardised: a format id is opaque
     * to everyone but its own manager.
     */
    private static final int FORMAT_ID = 0x4F524248;

    private static final Logger LOG = System.getLogger(GlassFishTransactionBridge.class.getName());

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AtomicLong sequence = new AtomicLong();

    @Inject
    private JavaEETransactionManager transactions;

    @Override
    public void recreate(Xid xid, long timeoutSeconds) throws TransactionException {
        try {
            transactions.recreate(xid, timeoutSeconds);
        } catch (Exception e) {
            throw failure("cannot recreate " + Xids.key(xid), XAException.XAER_RMERR, e);
        }
    }

    @Override
    public void release(Xid xid) throws TransactionException {
        try {
            transactions.release(xid);
        } catch (Exception e) {
            throw failure("cannot release " + Xids.key(xid), XAException.XAER_RMERR, e);
        } finally {
            detach();
        }
    }

    /**
     * Leaves the thread with no transaction on it, whatever happened above.
     *
     * <p>A release can fail - a branch the bean marked for rollback is the
     * ordinary case - and the caller logs that and carries on, because the
     * invocation's own outcome has already been decided. What must not carry
     * on is the association: these are pooled request threads, and one still
     * holding an aborted transaction fails the next request to land on it,
     * with an error about a transaction that request never started. That is a
     * failure in one call reappearing as a failure in an unrelated one, which
     * is the hardest kind to trace back.
     */
    private void detach() {
        try {
            transactions.suspend();
        } catch (Exception e) {
            // Nothing further to try, and throwing here would replace the real
            // failure with this one.
            LOG.log(Level.WARNING, "could not detach the transaction from this thread", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Nothing to do here, and the reason is worth stating rather than
     * leaving as an empty method. Synchronizations registered by beans that
     * ran in this branch are driven by the transaction manager as part of
     * preparing it - that is where the inflow contract puts them. Running them
     * here as well would run them twice.
     */
    @Override
    public void beforeCompletion(Xid xid) {
    }

    @Override
    public int prepare(Xid xid) throws TransactionException {
        try {
            return terminator().prepare(xid);
        } catch (XAException e) {
            throw failure("prepare failed for " + Xids.key(xid), e.errorCode, e);
        }
    }

    @Override
    public void commit(Xid xid, boolean onePhase) throws TransactionException {
        try {
            terminator().commit(xid, onePhase);
        } catch (XAException e) {
            throw failure("commit failed for " + Xids.key(xid), e.errorCode, e);
        }
    }

    @Override
    public void rollback(Xid xid) throws TransactionException {
        try {
            terminator().rollback(xid);
        } catch (XAException e) {
            throw failure("rollback failed for " + Xids.key(xid), e.errorCode, e);
        }
    }

    @Override
    public void forget(Xid xid) throws TransactionException {
        try {
            terminator().forget(xid);
        } catch (XAException e) {
            throw failure("forget failed for " + Xids.key(xid), e.errorCode, e);
        }
    }

    @Override
    public Xid[] recover(int flags) throws TransactionException {
        try {
            Xid[] found = terminator().recover(flags);
            return found == null ? new Xid[0] : found;
        } catch (XAException e) {
            throw failure("recover failed", e.errorCode, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>A client-driven transaction is begun as an imported branch and then
     * suspended, rather than left running on the thread that asked for it.
     * The request that begins a transaction is not the request that uses it -
     * over HTTP they are separate exchanges, possibly on different threads -
     * so a transaction still attached to the beginning thread would leak into
     * whatever that thread did next.
     */
    @Override
    public Xid begin(long timeoutSeconds) throws TransactionException {
        Xid xid = newXid();
        recreate(xid, timeoutSeconds);
        release(xid);
        return xid;
    }

    /**
     * {@inheritDoc}
     *
     * <p>One phase: this server is the only resource manager in a transaction
     * the client began here, so there is nobody to agree with and a prepare
     * would be a round trip spent asking ourselves.
     */
    @Override
    public void commitUserTransaction(Xid xid) throws TransactionException {
        commit(xid, true);
    }

    @Override
    public void rollbackUserTransaction(Xid xid) throws TransactionException {
        rollback(xid);
    }

    private Xid newXid() {
        byte[] global = new byte[24];
        RANDOM.nextBytes(global);
        // The counter makes two branches begun in the same nanosecond on the
        // same node distinct without relying on the random bytes alone.
        ByteBuffer.wrap(global, 0, Long.BYTES).putLong(sequence.incrementAndGet());
        return new Xids.SimpleXid(FORMAT_ID, global, new byte[] { 1 });
    }

    private XATerminator terminator() throws TransactionException {
        XATerminator terminator = transactions == null ? null : transactions.getXATerminator();
        if (terminator == null) {
            throw new TransactionException("this server has no transaction manager to drive",
                    XAException.XAER_RMFAIL);
        }
        return terminator;
    }

    private static TransactionException failure(String message, int errorCode, Throwable cause) {
        return new TransactionException(message + ": " + cause, errorCode == 0
                ? XAException.XAER_RMERR : errorCode, cause);
    }

    /** Exposed so the endpoint can report what it wired without reflection. */
    static int formatId() {
        return FORMAT_ID;
    }

    static int xaOk() {
        return XAResource.XA_OK;
    }
}
