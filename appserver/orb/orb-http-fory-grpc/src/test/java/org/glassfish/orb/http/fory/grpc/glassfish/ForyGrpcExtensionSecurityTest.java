/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.orb.http.fory.grpc.glassfish;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.glassfish.internal.data.ApplicationRegistry;
import org.glassfish.orb.http.fory.grpc.ForyGeneratedRuntime;
import org.glassfish.orb.http.fory.grpc.ForyGeneratedSchemaAdapter;
import org.glassfish.orb.http.fory.grpc.ForyGrpcSkeleton;
import org.glassfish.orb.http.fory.grpc.ForyRuntimeModelGenerator;
import org.glassfish.orb.http.glassfish.GlassFishContainerBridge;
import org.glassfish.orb.http.glassfish.GlassFishSecurityBridge;
import org.glassfish.orb.http.glassfish.GlassFishTransactionBridge;
import org.glassfish.orb.http.protocol.EjbKey;
import org.glassfish.orb.http.server.ContainerBridge.NoSuchTargetException;
import org.glassfish.orb.http.server.ServerExchange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The real {@link ForyGrpcExtension}, with the three bridges replaced by
 * recorders. The identity the request established must be handed back exactly
 * once: whichever way the lookup fails, and after a call that succeeds.
 */
class ForyGrpcExtensionSecurityTest {

    private static final String PATH = "/fory/demo.Greeter/greet";

    public interface Greeter {
        String greet(String name);
    }

    @Test
    void clearsTheSecurityTokenWhenTheBeanCannotBeResolved() throws Exception {
        List<String> events = new ArrayList<>();

        dispatch(events, new GlassFishContainerBridge() {
            @Override
            public EjbKey resolve(String app, String module, String distinct, String bean, byte[] session)
                    throws NoSuchTargetException {
                events.add("resolve");
                throw new NoSuchTargetException("bean is stateful");
            }
        }, new byte[0]);

        assertEquals(List.of("establish:alice", "resolve", "clear:token"), events,
                "the identity established for the request was never cleared");
    }

    @Test
    void clearsTheSecurityTokenWhenTheTargetCannotBeObtained() throws Exception {
        List<String> events = new ArrayList<>();

        dispatch(events, new GlassFishContainerBridge() {
            @Override
            public EjbKey resolve(String app, String module, String distinct, String bean, byte[] session) {
                events.add("resolve");
                return new EjbKey(1L, new byte[] {0, 0, 0, 1});
            }

            @Override
            public Object getTargetObject(EjbKey key, String viewClassName) throws NoSuchTargetException {
                events.add("getTargetObject");
                throw new NoSuchTargetException("no live instance");
            }

            @Override
            public void releaseTargetObject(Object target) {
                events.add("release");
            }
        }, new byte[0]);

        assertEquals(List.of("establish:alice", "resolve", "getTargetObject", "clear:token"), events,
                "the identity established for the request was never cleared");
    }

    @Test
    void clearsTheSecurityTokenExactlyOnceWhenTheCallSucceeds() throws Exception {
        List<String> events = new ArrayList<>();
        Greeter bean = name -> "hello " + name;

        dispatch(events, new GlassFishContainerBridge() {
            @Override
            public EjbKey resolve(String app, String module, String distinct, String bean, byte[] session) {
                events.add("resolve");
                return new EjbKey(1L, new byte[] {0, 0, 0, 1});
            }

            @Override
            public Object getTargetObject(EjbKey key, String viewClassName) {
                events.add("getTargetObject");
                return bean;
            }

            @Override
            public void releaseTargetObject(Object target) {
                events.add(target == bean ? "release" : "release:other");
            }
        }, requestFrame("bob"));

        assertEquals(List.of("establish:alice", "resolve", "getTargetObject", "release", "clear:token"), events,
                "the identity must be cleared once, after the target is released");
    }

    private static byte[] requestFrame(String argument) {
        var models = ForyRuntimeModelGenerator.unary("generated.security", "greet", String.class, String.class);
        byte[] payload = new ForyGeneratedRuntime(models, 2000, 2001).serialize(argument);
        byte[] frame = new byte[payload.length + 5];
        frame[1] = (byte) (payload.length >>> 24);
        frame[2] = (byte) (payload.length >>> 16);
        frame[3] = (byte) (payload.length >>> 8);
        frame[4] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, 5, payload.length);
        return frame;
    }

    private static void dispatch(List<String> events, GlassFishContainerBridge container, byte[] requestBody)
            throws Exception {
        Object token = new Object();

        ForyGrpcExtension extension = new ForyGrpcExtension();
        set(extension, "applications", new ApplicationRegistry());
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
            }
        });
        set(extension, "transactions", new GlassFishTransactionBridge() {
            @Override
            public void detach() {
            }
        });
        extension.postConstruct();

        // Publish one route the way refreshForyRegistry would, without a deployment.
        ForyEjbIndex index = indexOf(extension);
        var models = ForyRuntimeModelGenerator.unary("generated.security", "greet", String.class, String.class);
        index.foryRegistry().register(PATH, PATH, ForyGrpcSkeleton.of("demo.Greeter", Greeter.class), models,
                new ForyGeneratedRuntime(models, 2000, 2001),
                ForyGeneratedSchemaAdapter.unary(models.request(), models.response()));
        @SuppressWarnings("unchecked")
        Map<String, Object> routes = (Map<String, Object>) get(index, "foryRoutes");
        routes.put(PATH, new ForyEjbIndex.ForyRoute("app", "module", "bean", Greeter.class.getName()));

        extension.dispatch(PATH, new Exchange(requestBody));
    }

    private static ForyEjbIndex indexOf(ForyGrpcExtension extension) throws Exception {
        Object adapter = get(extension, "foryGrpc");
        Object targets = get(adapter, "targets");
        for (Field field : targets.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            if (field.get(targets) instanceof ForyEjbIndex index) {
                return index;
            }
        }
        throw new AssertionError("no ForyEjbIndex captured by " + targets.getClass());
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static final class Exchange implements ServerExchange {
        private final byte[] body;

        Exchange(byte[] body) {
            this.body = body;
        }

        @Override public String method() { return "POST"; }
        @Override public byte[] pathBytes() { return PATH.getBytes(StandardCharsets.UTF_8); }
        @Override public int pathOffset() { return 0; }
        @Override public int pathLength() { return pathBytes().length; }
        @Override public String requestHeader(String name) { return null; }
        @Override public String queryParameter(String name) { return null; }
        @Override public InputStream requestBody() { return new ByteArrayInputStream(body); }
        @Override public void setStatus(int status) { }
        @Override public void setResponseHeader(String name, String value) { }
        @Override public boolean supportsResponseTrailers() { return true; }
        @Override public void setResponseTrailer(String name, String value) { }
        @Override public void writeBody(ByteBuffer[] buffers) throws IOException { }
        @Override public String authenticatedUser() { return "alice"; }
    }
}