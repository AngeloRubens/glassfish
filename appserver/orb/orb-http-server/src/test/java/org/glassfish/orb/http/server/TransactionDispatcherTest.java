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
import java.util.Map;

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
    }

    @Test
    @DisplayName("an anonymous caller may not list the in-doubt branches")
    void anonymousRecoverIs403() throws Exception {
        FakeTransactions transactions = new FakeTransactions();
        TransactionDispatcher dispatcher = new TransactionDispatcher(transactions);
        Loopback.InMemoryExchange exchange = exchange("GET", TxRoutes.recoverPath(Protocol.CONTEXT_PATH));
        exchange.setAuthenticatedUser(null);

        dispatcher.dispatch(exchange);

        assertEquals(Protocol.SC_FORBIDDEN, exchange.toResponse().status());
    }
}
