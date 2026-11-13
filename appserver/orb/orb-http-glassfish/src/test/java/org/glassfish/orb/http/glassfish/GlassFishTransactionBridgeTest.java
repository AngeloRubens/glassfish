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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.sun.enterprise.transaction.api.JavaEETransactionManager;

import jakarta.resource.spi.XATerminator;

import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import org.glassfish.orb.http.protocol.Xids;
import org.glassfish.orb.http.server.TransactionBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the bridge does when there is no transaction manager behind it.
 * <p>
 * Injected at runtime and absent here, which is the same shape as a server
 * whose transaction service failed to start. Every operation has to fail as a
 * transaction failure the caller can act on - an XA error code and a sentence -
 * rather than as a null dereference that reaches the client as a generic
 * server error.
 */
class GlassFishTransactionBridgeTest {

    private static final Xid XID = new Xids.SimpleXid(1, new byte[] { 1, 2 }, new byte[] { 3 });

    private final GlassFishTransactionBridge bridge = new GlassFishTransactionBridge();

    @Test
    @DisplayName("with no manager, driving a branch fails as a transaction failure")
    void theXaFamilyFailsCleanly() {
        assertTransactionFailure(() -> bridge.prepare(XID));
        assertTransactionFailure(() -> bridge.commit(XID, true));
        assertTransactionFailure(() -> bridge.rollback(XID));
        assertTransactionFailure(() -> bridge.forget(XID));
        assertTransactionFailure(() -> bridge.recover(0));
    }

    @Test
    @DisplayName("with no manager, importing and beginning fail rather than pretending")
    void theImportAndBeginFailCleanly() {
        assertTransactionFailure(() -> bridge.recreate(XID, 0));
        // begin returning an xid here would hand the caller a transaction
        // nothing can ever commit.
        assertTransactionFailure(() -> bridge.begin(30));
    }

    @Test
    @DisplayName("the failure names an XA error code, which is what a coordinator reads")
    void theErrorCodeIsUsable() {
        TransactionBridge.TransactionException thrown = assertThrows(
                TransactionBridge.TransactionException.class, () -> bridge.prepare(XID));

        assertEquals(XAException.XAER_RMFAIL, thrown.errorCode());
        assertTrue(thrown.getMessage().contains("transaction manager"), thrown.getMessage());
    }

    @Test
    @DisplayName("before-completion does nothing and says nothing, by design")
    void beforeCompletionIsSilent() throws Exception {
        // The manager runs synchronizations as part of preparing the branch.
        // Doing it here as well would run them twice, so this is deliberately
        // empty - and must stay safe to call with no manager at all.
        bridge.beforeCompletion(XID);
    }

    @Test
    @DisplayName("a branch this transport did not begin is not driven")
    void foreignXidIsNotDriven() throws Exception {
        List<String> terminatorCalls = new ArrayList<>();
        XATerminator terminator = (XATerminator) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { XATerminator.class },
                (proxy, method, args) -> {
                    terminatorCalls.add(method.getName());
                    return method.getName().equals("recover") ? new Xid[0] : null;
                });
        InvocationHandler managerHandler = (proxy, method, args) ->
                method.getName().equals("getXATerminator") ? terminator : null;
        JavaEETransactionManager manager = (JavaEETransactionManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { JavaEETransactionManager.class }, managerHandler);
        Field field = GlassFishTransactionBridge.class.getDeclaredField("transactions");
        field.setAccessible(true);
        field.set(bridge, manager);

        // XID has format id 1, not the bridge's own.
        TransactionBridge.TransactionException thrown = assertThrows(
                TransactionBridge.TransactionException.class, () -> bridge.rollback(XID));

        assertEquals(XAException.XAER_NOTA, thrown.errorCode());
        assertTrue(terminatorCalls.isEmpty(), "terminator was called: " + terminatorCalls);
    }

    private static final Xid OWN = new Xids.SimpleXid(GlassFishTransactionBridge.formatId(),
            new byte[] { 1, 2 }, new byte[] { 3 });

    /** Wires a manager whose terminator records its calls and lists {@code listed} on recover. */
    private void wire(List<String> terminatorCalls, Xid... listed) throws Exception {
        XATerminator terminator = (XATerminator) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { XATerminator.class },
                (proxy, method, args) -> {
                    terminatorCalls.add(method.getName());
                    if (method.getName().equals("recover")) {
                        return listed;
                    }
                    return method.getReturnType() == int.class ? Integer.valueOf(0) : null;
                });
        InvocationHandler managerHandler = (proxy, method, args) ->
                method.getName().equals("getXATerminator") ? terminator : null;
        JavaEETransactionManager manager = (JavaEETransactionManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { JavaEETransactionManager.class }, managerHandler);
        Field field = GlassFishTransactionBridge.class.getDeclaredField("transactions");
        field.setAccessible(true);
        field.set(bridge, manager);
    }

    private static void assertNotDriven(List<String> terminatorCalls, Executable operation) {
        TransactionBridge.TransactionException thrown = assertThrows(
                TransactionBridge.TransactionException.class, operation::run);
        assertEquals(XAException.XAER_NOTA, thrown.errorCode());
        assertTrue(terminatorCalls.isEmpty(), "terminator was called: " + terminatorCalls);
    }

    @Test
    @DisplayName("prepare, commit, forget and the user-transaction ends refuse a foreign branch too")
    void everyOperationRefusesAForeignXid() throws Exception {
        List<String> terminatorCalls = new ArrayList<>();
        wire(terminatorCalls);

        assertNotDriven(terminatorCalls, () -> bridge.prepare(XID));
        assertNotDriven(terminatorCalls, () -> bridge.commit(XID, false));
        assertNotDriven(terminatorCalls, () -> bridge.commit(XID, true));
        assertNotDriven(terminatorCalls, () -> bridge.forget(XID));
        assertNotDriven(terminatorCalls, () -> bridge.commitUserTransaction(XID));
        assertNotDriven(terminatorCalls, () -> bridge.rollbackUserTransaction(XID));
    }

    @Test
    @DisplayName("recover lists only the branches this transport began")
    void recoverListsOnlyOwnBranches() throws Exception {
        List<String> terminatorCalls = new ArrayList<>();
        Xid other = new Xids.SimpleXid(GlassFishTransactionBridge.formatId() + 1,
                new byte[] { 9 }, new byte[] { 9 });
        wire(terminatorCalls, XID, OWN, other);

        Xid[] listed = bridge.recover(0);

        assertEquals(1, listed.length);
        assertTrue(Xids.sameXid(OWN, listed[0]), "listed: " + Arrays.toString(listed));
    }

    @Test
    @DisplayName("a branch of this transport's own format is still driven")
    void ownXidIsDriven() throws Exception {
        List<String> terminatorCalls = new ArrayList<>();
        wire(terminatorCalls);

        bridge.rollback(OWN);
        bridge.forget(OWN);
        bridge.commit(OWN, false);
        bridge.prepare(OWN);

        assertEquals(List.of("rollback", "forget", "commit", "prepare"), terminatorCalls);
    }

    private static void assertTransactionFailure(Executable operation) {
        assertThrows(TransactionBridge.TransactionException.class, operation::run);
    }

    private interface Executable {
        void run() throws Exception;
    }
}
