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
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

import java.net.URI;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import javax.naming.Context;
import javax.naming.Name;
import javax.naming.spi.InitialContextFactory;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.glassfish.orb.http.protocol.Xids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A transaction this client did not start must still travel.
 * <p>
 * This is the case an application server makes when it calls another one, and
 * the one an application actually writes: nothing touches this transport's own
 * UserTransaction, the container began the transaction, and the remote call
 * has to become part of it rather than run beside it.
 */
class AmbientTransactionTest {

    static final Map<String, Object> BOUND = new HashMap<>();

    static Xid started;

    private ClientConfiguration config;

    private RecordingTransport transport;

    @BeforeEach
    void setUp() {
        BOUND.clear();
        started = null;
        AmbientTransaction.forget();
        ClientTransactionContext.disassociate();
        System.setProperty(Context.INITIAL_CONTEXT_FACTORY, Factory.class.getName());

        config = ClientConfiguration.builder(URI.create("http://localhost:8080/glassfish-services")).build();
        transport = new RecordingTransport();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(Context.INITIAL_CONTEXT_FACTORY);
        ClientTransactionContext.disassociate();
        AmbientTransaction.forget();
    }

    @Test
    @DisplayName("a container's transaction is joined, so the call carries it")
    void anAmbientTransactionIsJoined() {
        FakeTransaction transaction = new FakeTransaction();
        BOUND.put("java:comp/TransactionSynchronizationRegistry", new FakeRegistry(transaction));
        BOUND.put("java:appserver/TransactionManager", new FakeManager(transaction));

        AmbientTransaction.join(config, transport);

        // Enlisted, the caller's manager started the branch, and from here on
        // every invocation carries it.
        assertEquals(1, transaction.enlisted, "the resource was not enlisted");
        assertNotNull(started, "the manager never started the branch");
        assertNotNull(ClientTransactionContext.current());
    }

    @Test
    @DisplayName("the same endpoint is joined once per transaction, not once per call")
    void joiningIsIdempotent() {
        FakeTransaction transaction = new FakeTransaction();
        FakeRegistry registry = new FakeRegistry(transaction);
        BOUND.put("java:comp/TransactionSynchronizationRegistry", registry);
        BOUND.put("java:appserver/TransactionManager", new FakeManager(transaction));

        AmbientTransaction.join(config, transport);
        ClientTransactionContext.disassociate();
        AmbientTransaction.join(config, transport);

        // Enlisting twice would make one server two branches of the same
        // transaction, and the coordinator would prepare it twice.
        assertEquals(1, transaction.enlisted);
    }

    @Test
    @DisplayName("a plain client with no transaction manager is left alone")
    void noManagerMeansNoTransaction() {
        // Nothing bound: the ordinary standalone client.
        AmbientTransaction.join(config, transport);

        assertNull(ClientTransactionContext.current());
        assertEquals(0, transport.calls.size(), "joining must not talk to the server");
    }

    @Test
    @DisplayName("a transaction this client already began is not joined again")
    void anOwnTransactionIsLeftAsItIs() {
        Xid ours = new Xids.SimpleXid(1, new byte[] { 7 }, new byte[] { 7 });
        ClientTransactionContext.associate(ours, 0);

        FakeTransaction transaction = new FakeTransaction();
        BOUND.put("java:comp/TransactionSynchronizationRegistry", new FakeRegistry(transaction));
        BOUND.put("java:appserver/TransactionManager", new FakeManager(transaction));

        AmbientTransaction.join(config, transport);

        assertEquals(0, transaction.enlisted);
        assertEquals(ours, ClientTransactionContext.current());
    }

    // ---- doubles ---------------------------------------------------------

    /** Binds the names this class looks up; everything else is absent. */
    public static final class Factory implements InitialContextFactory {
        @Override
        public Context getInitialContext(Hashtable<?, ?> environment) {
            return (Context) java.lang.reflect.Proxy.newProxyInstance(
                    Factory.class.getClassLoader(), new Class<?>[] { Context.class },
                    (proxy, method, args) -> {
                        if ("lookup".equals(method.getName()) && args != null && args.length == 1) {
                            Object name = args[0] instanceof Name n ? n.toString() : args[0];
                            Object bound = BOUND.get(String.valueOf(name));
                            if (bound == null) {
                                throw new javax.naming.NameNotFoundException(String.valueOf(name));
                            }
                            return bound;
                        }
                        if ("close".equals(method.getName())) {
                            return null;
                        }
                        return null;
                    });
        }
    }

    static final class FakeTransaction implements Transaction {
        int enlisted;

        @Override
        public boolean enlistResource(XAResource resource) throws jakarta.transaction.SystemException {
            enlisted++;
            try {
                // What a real manager does next, and what associates the
                // branch on this side.
                Xid xid = new Xids.SimpleXid(0x4A5441, new byte[] { 1, 2 }, new byte[] { 3 });
                resource.start(xid, XAResource.TMNOFLAGS);
                started = xid;
            } catch (Exception e) {
                throw new jakarta.transaction.SystemException(e.toString());
            }
            return true;
        }

        @Override
        public void commit() {
        }

        @Override
        public boolean delistResource(XAResource resource, int flag) {
            return true;
        }

        @Override
        public int getStatus() {
            return Status.STATUS_ACTIVE;
        }

        @Override
        public void registerSynchronization(Synchronization sync) {
        }

        @Override
        public void rollback() {
        }

        @Override
        public void setRollbackOnly() {
        }
    }

    static final class FakeManager implements TransactionManager {
        private final Transaction transaction;

        FakeManager(Transaction transaction) {
            this.transaction = transaction;
        }

        @Override
        public Transaction getTransaction() {
            return transaction;
        }

        @Override
        public void begin() {
        }

        @Override
        public void commit() {
        }

        @Override
        public int getStatus() {
            return Status.STATUS_ACTIVE;
        }

        @Override
        public void resume(Transaction tx) {
        }

        @Override
        public void rollback() {
        }

        @Override
        public void setRollbackOnly() {
        }

        @Override
        public void setTransactionTimeout(int seconds) {
        }

        @Override
        public Transaction suspend() {
            return transaction;
        }
    }

    static final class FakeRegistry implements TransactionSynchronizationRegistry {
        private final Map<Object, Object> resources = new HashMap<>();
        private final Transaction transaction;

        FakeRegistry(Transaction transaction) {
            this.transaction = transaction;
        }

        @Override
        public Object getTransactionKey() {
            return transaction;
        }

        @Override
        public Object getResource(Object key) {
            return resources.get(key);
        }

        @Override
        public void putResource(Object key, Object value) {
            resources.put(key, value);
        }

        @Override
        public boolean getRollbackOnly() {
            return false;
        }

        @Override
        public int getTransactionStatus() {
            return Status.STATUS_ACTIVE;
        }

        @Override
        public void registerInterposedSynchronization(Synchronization sync) {
        }

        @Override
        public void setRollbackOnly() {
        }
    }
}
