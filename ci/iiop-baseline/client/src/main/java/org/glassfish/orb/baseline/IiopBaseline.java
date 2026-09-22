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

import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.InitialContext;

/**
 * The baseline over RMI-IIOP: what a remote view carries today.
 *
 * <p>These three properties are the "before" side of the migration this work
 * proposes, written out rather than left to the jndi.properties inside the
 * GlassFish jars, so that the two client shapes are comparable line by line.
 */
public final class IiopBaseline {

    private IiopBaseline() {
    }

    public static void main(String[] args) throws Exception {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY,
                "com.sun.enterprise.naming.SerialInitContextFactory");
        environment.put("org.omg.CORBA.ORBInitialHost", System.getProperty("orb.host", "localhost"));
        environment.put("org.omg.CORBA.ORBInitialPort", System.getProperty("orb.port", "3700"));

        InitialContext context = new InitialContext(environment);
        try {
            System.exit(Baseline.run(context, "RMI-IIOP") == 0 ? 0 : 1);
        } finally {
            context.close();
        }
    }
}
