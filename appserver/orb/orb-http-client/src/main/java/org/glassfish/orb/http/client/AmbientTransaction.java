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

import jakarta.transaction.Status;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.naming.InitialContext;
import javax.naming.NamingException;

/**
 * Joins a transaction this client did not start.
 * <p>
 * {@link HttpUserTransaction} covers the case where the client begins the
 * transaction itself. It does not cover the more common one: code running
 * inside a container, in a transaction the container began, calling a remote
 * bean. Nothing there ever touches this transport's own UserTransaction, and
 * without this the call would leave the transaction behind - the bean would
 * run in its own, and work the caller believes is one unit would be two.
 * <p>
 * The join is the ordinary JTA one. An {@link HttpXAResource} is enlisted with
 * the caller's transaction, the caller's transaction manager calls
 * {@code start} on it, and from then on invocations carry the branch because
 * {@link ClientTransactionContext} has been associated. At the end the
 * caller's coordinator drives prepare and commit over this transport, which is
 * what makes the remote server a genuine participant rather than a side
 * effect.
 * <p>
 * Everything here is best effort and silent when there is nothing to join. In
 * a plain client JVM there is no transaction manager, the lookup fails once,
 * and the answer is remembered so later invocations cost nothing.
 */
final class AmbientTransaction {

    private static final Logger LOG = System.getLogger(AmbientTransaction.class.getName());

    /**
     * Where a transaction manager is usually bound. The registry has a
     * standard name; the manager does not, so the container-specific names
     * are tried in turn and the answer is cached either way.
     */
    private static final List<String> MANAGER_NAMES = List.of(
            System.getProperty("org.glassfish.orb.http.transactionManager", "java:appserver/TransactionManager"),
            "java:comp/TransactionManager",
            "java:/TransactionManager",
            "java:jboss/TransactionManager");

    private static final String REGISTRY_NAME = "java:comp/TransactionSynchronizationRegistry";

    /** Set once we know this JVM has no transaction manager to join. */
    private static final AtomicBoolean UNAVAILABLE = new AtomicBoolean();

    private static volatile TransactionManager manager;

    private static volatile TransactionSynchronizationRegistry registry;

    private AmbientTransaction() {
    }

    /**
     * Discards what was found about this JVM's transaction manager.
     *
     * <p>Visible for tests. The lookups are cached deliberately - a plain
     * client JVM must not pay for them on every invocation - and a cache that
     * cannot be cleared would make the first test to run decide the answer for
     * all the others.
     */
    static void forget() {
        manager = null;
        registry = null;
        UNAVAILABLE.set(false);
    }

    /**
     * Enlists this endpoint with the caller's transaction, if there is one and
     * it has not been enlisted already.
     *
     * @param config    names the endpoint, which is also what distinguishes
     *                  branches: two calls to the same server in one
     *                  transaction are one branch, to different servers are two
     * @param transport how the branch will be driven
     */
    static void join(ClientConfiguration config, HttpTransport transport) {
        if (ClientTransactionContext.current() != null || UNAVAILABLE.get()) {
            // Already in a transaction this client knows about, or there is no
            // transaction manager here to ask.
            return;
        }

        TransactionSynchronizationRegistry synchronizations = registry();
        if (synchronizations == null || synchronizations.getTransactionKey() == null) {
            return;
        }

        String marker = AmbientTransaction.class.getName() + ':' + config.baseUri();
        if (synchronizations.getResource(marker) != null) {
            return;
        }

        try {
            TransactionManager transactions = manager();
            if (transactions == null) {
                LOG.log(Level.WARNING, "a transaction is active but no transaction manager was found,"
                        + " so the call to " + config.baseUri() + " will not carry it");
                return;
            }
            Transaction current = transactions.getTransaction();
            if (current == null || current.getStatus() != Status.STATUS_ACTIVE) {
                return;
            }
            // Enlisting is what makes the server a participant: the caller's
            // coordinator will drive prepare and commit on this resource.
            current.enlistResource(new HttpXAResource(config, transport));
            synchronizations.putResource(marker, Boolean.TRUE);
        } catch (Exception e) {
            // Not fatal to the invocation, and deliberately loud: a call that
            // silently leaves its transaction behind is worse than one that
            // fails, because the damage shows up somewhere else.
            LOG.log(Level.WARNING, "could not join the caller's transaction for " + config.baseUri(), e);
        }
    }

    private static TransactionSynchronizationRegistry registry() {
        TransactionSynchronizationRegistry known = registry;
        if (known != null) {
            return known;
        }
        Object found = lookup(REGISTRY_NAME);
        if (found instanceof TransactionSynchronizationRegistry resolved) {
            registry = resolved;
            return resolved;
        }
        // No registry means no container transaction to join. Say so once.
        UNAVAILABLE.set(true);
        return null;
    }

    private static TransactionManager manager() {
        TransactionManager known = manager;
        if (known != null) {
            return known;
        }
        for (String name : MANAGER_NAMES) {
            if (lookup(name) instanceof TransactionManager resolved) {
                manager = resolved;
                return resolved;
            }
        }
        return null;
    }

    private static Object lookup(String name) {
        try {
            return new InitialContext().lookup(name);
        } catch (NamingException | RuntimeException e) {
            return null;
        }
    }
}
