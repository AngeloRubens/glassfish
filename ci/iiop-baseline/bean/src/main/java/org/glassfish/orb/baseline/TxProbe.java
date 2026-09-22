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

    // One method per transaction attribute. Each answers the same question -
    // which transaction am I in - and the attribute decides what the container
    // must have done with the caller's before the method ran.

    /** REQUIRED: joins the caller's transaction, or starts one if there is none. */
    String required();

    /** REQUIRES_NEW: always its own transaction, the caller's suspended. */
    String requiresNew();

    /** MANDATORY: joins the caller's, and refuses to run without one. */
    String mandatory();

    /** SUPPORTS: joins the caller's if there is one, runs outside if not. */
    String supports();

    /** NOT_SUPPORTED: never in a transaction; the caller's is suspended. */
    String notSupported();

    /** NEVER: refuses to run if the caller has a transaction at all. */
    String never();
}
