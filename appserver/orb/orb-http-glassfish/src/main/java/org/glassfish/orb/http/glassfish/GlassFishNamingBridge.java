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


import com.sun.enterprise.naming.impl.ProviderManager;
import com.sun.enterprise.naming.impl.SerialContextProvider;

import jakarta.inject.Singleton;

import java.util.HashMap;
import java.util.Map;

import javax.naming.NamingException;

import org.glassfish.orb.http.server.NamingBridge;
import org.jvnet.hk2.annotations.Service;

/**
 * The naming half of the adapter, and the part with nothing to translate.
 *
 * <p>{@code SerialContextProvider} is the remote interface GlassFish already
 * publishes over IIOP for JNDI, and its nine methods are the nine operations
 * of {@link NamingBridge}. The claim in the transport's design notes that this
 * mapping is one-to-one is not a figure of speech: every method below is a
 * delegation and nothing else.
 *
 * <p>The local provider is used rather than the remote one. The remote
 * provider exists to be reached through an ORB; we are already inside the
 * server, so going out through a remote reference to come back to the same
 * namespace would add a hop and an ORB dependency to no purpose.
 */
@Service
@Singleton
public class GlassFishNamingBridge implements NamingBridge {

    private SerialContextProvider provider() {
        return ProviderManager.getProviderManager().getLocalProvider();
    }

    @Override
    public Object lookup(String name) throws NamingException {
        try {
            return provider().lookup(name);
        } catch (java.rmi.RemoteException e) {
            throw asNamingException("lookup", name, e);
        }
    }

    @Override
    public Object lookupLink(String name) throws NamingException {
        // The provider does not distinguish the two; SerialContext resolves
        // links above this level.
        return lookup(name);
    }

    @Override
    public void bind(String name, Object value) throws NamingException {
        try {
            provider().bind(name, value);
        } catch (java.rmi.RemoteException e) {
            throw asNamingException("bind", name, e);
        }
    }

    @Override
    public void rebind(String name, Object value) throws NamingException {
        try {
            provider().rebind(name, value);
        } catch (java.rmi.RemoteException e) {
            throw asNamingException("rebind", name, e);
        }
    }

    @Override
    public void unbind(String name) throws NamingException {
        try {
            provider().unbind(name);
        } catch (java.rmi.RemoteException e) {
            throw asNamingException("unbind", name, e);
        }
    }

    @Override
    public void rename(String oldName, String newName) throws NamingException {
        try {
            provider().rename(oldName, newName);
        } catch (java.rmi.RemoteException e) {
            throw asNamingException("rename", oldName, e);
        }
    }

    @Override
    public Map<String, Object> list(String name) throws NamingException {
        try {
            Map<String, Object> result = new HashMap<>();
            provider().list(name).forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        } catch (java.rmi.RemoteException e) {
            throw asNamingException("list", name, e);
        }
    }

    @Override
    public void createSubcontext(String name) throws NamingException {
        try {
            provider().createSubcontext(name);
        } catch (java.rmi.RemoteException e) {
            throw asNamingException("createSubcontext", name, e);
        }
    }

    @Override
    public void destroySubcontext(String name) throws NamingException {
        try {
            provider().destroySubcontext(name);
        } catch (java.rmi.RemoteException e) {
            throw asNamingException("destroySubcontext", name, e);
        }
    }

    private static NamingException asNamingException(String operation, String name, Throwable cause) {
        NamingException e = new NamingException(operation + ' ' + name + " failed");
        e.initCause(cause);
        return e;
    }
}
