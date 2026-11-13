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

package org.glassfish.orb.http.server;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import org.glassfish.orb.http.protocol.Protocol;
import org.glassfish.orb.http.protocol.TxRoutes;
import org.glassfish.orb.http.protocol.Xids;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may drive the transaction endpoints.
 */
class TransactionDispatcherTest {

    private static final Xid XID = new Xids.SimpleXid(0x4F524248, new byte[] { 1, 2, 3 }, new byte[] { 1 });

    private static Loopback.InMemoryExchange exchange(String method, String path) {
        return new Loopback.InMemoryExchange(method, path.getBytes(StandardCharsets.UTF_8), null,
                Map.of(), new byte[0]);
    }

    @Test
    @DisplayName("a credential the realm refuses is a 403, not an exception out of dispatch")
    void rejectedCredentialIs403() {
        FakeTransactions transactions = new FakeTransactions();
        TransactionDispatcher dispatcher = new TransactionDispatcher(transactions);
        Loopback.InMemoryExchange exchange = exchange("POST",
                TxRoutes.path(Protocol.CONTEXT_PATH, TxRoutes.FAMILY_XA, TxRoutes.OP_ROLLBACK, XID));
        exchange.setAuthenticatedUser(Loopback.InMemoryExchange.REJECT);

        assertDoesNotThrow(() -> dispatcher.dispatch(exchange));

        assertEquals(Protocol.SC_FORBIDDEN, exchange.toResponse().status());
        assertTrue(transactions.calls.stream().noneMatch(c -> c.startsWith("rollback")),
                "rollback must not run: " + transactions.calls);
        assertReasonIsFixed(exchange);
    }

    @Test
    @DisplayName("the same refusal on a user-transaction route, with nothing established or cleared")
    void rejectedCredentialOnUserTransactionRouteIs403() {
        FakeTransactions transactions = new FakeTransactions();
        RecordingSecurity security = new RecordingSecurity();
        TransactionDispatcher dispatcher = new TransactionDispatcher(transactions, security);
        Loopback.InMemoryExchange exchange = exchange("POST",
                TxRoutes.path(Protocol.CONTEXT_PATH, TxRoutes.FAMILY_UT, TxRoutes.OP_COMMIT, XID));
        exchange.setAuthenticatedUser(Loopback.InMemoryExchange.REJECT);

        assertDoesNotThrow(() -> dispatcher.dispatch(exchange));

        assertEquals(Protocol.SC_FORBIDDEN, exchange.toResponse().status());
        assertTrue(transactions.calls.isEmpty(), "bridge was reached: " + transactions.calls);
        assertTrue(security.events.isEmpty(), "security was touched: " + security.events);
        assertReasonIsFixed(exchange);
    }

    @Test
    @DisplayName("an accepted caller is established, served and cleared, also when the bridge fails")
    void acceptedCallerIsServedAndCleared() {
        FakeTransactions transactions = new FakeTransactions();
        RecordingSecurity security = new RecordingSecurity();
        TransactionDispatcher dispatcher = new TransactionDispatcher(transactions, security);

        Loopback.InMemoryExchange served = exchange("POST",
                TxRoutes.path(Protocol.CONTEXT_PATH, TxRoutes.FAMILY_XA, TxRoutes.OP_ROLLBACK, XID));
        served.setAuthenticatedUser("alice");
        assertDoesNotThrow(() -> dispatcher.dispatch(served));
        assertEquals(Protocol.SC_NO_CONTENT, served.toResponse().status());
        assertEquals(List.of("establish(alice)", "clear(token-alice)"), security.events);

        security.events.clear();
        transactions.failWith = XAException.XAER_RMERR;
        Loopback.InMemoryExchange failed = exchange("POST",
                TxRoutes.path(Protocol.CONTEXT_PATH, TxRoutes.FAMILY_XA, TxRoutes.OP_ROLLBACK, XID));
        failed.setAuthenticatedUser("alice");
        assertDoesNotThrow(() -> dispatcher.dispatch(failed));
        assertEquals(Protocol.SC_EXCEPTION, failed.toResponse().status());
        assertEquals(List.of("establish(alice)", "clear(token-alice)"), security.events);
    }

    private static void assertReasonIsFixed(Loopback.InMemoryExchange exchange) {
        List<String> reasons = exchange.toResponse().headers().get("X-GF-Reason");
        assertEquals(1, reasons == null ? 0 : reasons.size(), "reason header: " + reasons);
        String reason = reasons.get(0);
        assertTrue(reason.indexOf('\r') < 0 && reason.indexOf('\n') < 0, "line break in: " + reason);
        assertTrue(!reason.contains("authentication failed"), "exception text leaked: " + reason);
    }

    /** Records what the dispatcher asks of the security bridge. */
    private static final class RecordingSecurity implements SecurityBridge {
        final List<String> events = new ArrayList<>();

        @Override
        public Object establish(String userName) {
            events.add("establish(" + userName + ")");
            return "token-" + userName;
        }

        @Override
        public void clear(Object token) {
            events.add("clear(" + token + ")");
        }
    }
