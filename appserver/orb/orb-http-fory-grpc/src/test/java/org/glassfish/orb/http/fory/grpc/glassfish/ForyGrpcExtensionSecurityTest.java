/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.orb.http.fory.grpc.glassfish;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.glassfish.orb.http.glassfish.GlassFishContainerBridge;
import org.glassfish.orb.http.glassfish.GlassFishSecurityBridge;
import org.glassfish.orb.http.protocol.EjbKey;
import org.glassfish.orb.http.server.ContainerBridge.NoSuchTargetException;
import org.glassfish.orb.http.server.ServerExchange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The identity a request established must be handed back when acquiring the
 * target fails, because no target exists whose close would hand it back.
 */
class ForyGrpcExtensionSecurityTest {

    private static final ForyEjbIndex.ForyRoute ROUTE =
            new ForyEjbIndex.ForyRoute("app", "module", "bean", "demo.Greeter");

    private static final ServerExchange EXCHANGE = (ServerExchange) Proxy.newProxyInstance(
            ServerExchange.class.getClassLoader(), new Class<?>[] {ServerExchange.class},
            (proxy, method, args) -> {
                if (method.getName().equals("authenticatedUser")) {
                    return "alice";
                }
                throw new UnsupportedOperationException(method.getName());
            });

    private final List<String> events = new ArrayList<>();

    @Test
    void clearsTheSecurityTokenWhenTheBeanCannotBeResolved() throws Exception {
        NoSuchTargetException failure = new NoSuchTargetException("bean is stateful");

        ForyGrpcExtension extension = extension(new GlassFishContainerBridge() {
            @Override
            public EjbKey resolve(String app, String module, String distinct, String bean, byte[] session)
                    throws NoSuchTargetException {
                events.add("resolve");
                throw failure;
            }
        }, false);

        assertSame(failure, assertThrows(NoSuchTargetException.class, () -> extension.acquire(ROUTE, EXCHANGE)));
        assertEquals(List.of("establish:alice", "resolve", "clear:token"), events,
                "the identity established for the request was never cleared");
    }

    @Test
    void clearsTheSecurityTokenWithoutReleasingATargetThatWasNeverObtained() throws Exception {
        NoSuchTargetException failure = new NoSuchTargetException("no live instance");

        ForyGrpcExtension extension = extension(new GlassFishContainerBridge() {
            @Override
            public EjbKey resolve(String app, String module, String distinct, String bean, byte[] session) {
                events.add("resolve");
                return new EjbKey(1L, new byte[] {0, 0, 0, 1});
            }

            @Override
            public Object getTargetObject(EjbKey key, String viewClassName) throws NoSuchTargetException {
                events.add("getTargetObject");
                throw failure;
            }

            @Override
            public void releaseTargetObject(Object target) {
                events.add("release");
            }
        }, false);

        assertSame(failure, assertThrows(NoSuchTargetException.class, () -> extension.acquire(ROUTE, EXCHANGE)));
        assertEquals(List.of("establish:alice", "resolve", "getTargetObject", "clear:token"), events,
                "the identity established for the request was never cleared");
    }

    @Test
    void keepsTheOriginalFailureWhenClearingTheSecurityTokenFailsToo() throws Exception {
        NoSuchTargetException failure = new NoSuchTargetException("bean is stateful");

        ForyGrpcExtension extension = extension(new GlassFishContainerBridge() {
            @Override
            public EjbKey resolve(String app, String module, String distinct, String bean, byte[] session)
                    throws NoSuchTargetException {
                events.add("resolve");
                throw failure;
            }
        }, true);

        assertSame(failure, assertThrows(NoSuchTargetException.class, () -> extension.acquire(ROUTE, EXCHANGE)));
        assertEquals(1, failure.getSuppressed().length, "the clear failure was not recorded");
        assertEquals("clear failed", failure.getSuppressed()[0].getMessage());
        assertEquals(List.of("establish:alice", "resolve", "clear:token"), events);
    }

    private ForyGrpcExtension extension(GlassFishContainerBridge container, boolean clearFails) throws Exception {
        Object token = new Object();
        ForyGrpcExtension extension = new ForyGrpcExtension();
        set(extension, "container", container);
        set(extension, "security", new GlassFishSecurityBridge() {
            @Override
            public Object establish(String userName) {
                events.add("establish:" + userName);
                return token;
            }

            @Override
            public void clear(Object t) {
                events.add(t == token ? "clear:token" : "clear:other");
                if (clearFails) {
                    throw new IllegalStateException("clear failed");
                }
            }
        });
        return extension;
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
