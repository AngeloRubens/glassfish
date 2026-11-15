/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package demo;

import jakarta.ejb.Stateless;

@Stateless
public class GreeterBean implements Greeter {

    @Override
    public String sayHello(String name) {
        return "Hello " + name;
    }

    @Override
    public int twice(int value) {
        return value * 2;
    }

    @Override
    public void ping() {
    }

    @Override
    public int answer() {
        return 42;
    }

    @Override
    public long plusOne(long value) {
        return value + 1;
    }

    @Override
    public boolean negate(boolean value) {
        return !value;
    }

    @Override
    public double half(double value) {
        return value / 2;
    }

    @Override
    public byte nextByte(byte value) {
        return (byte) (value + 1);
    }

    @Override
    public short nextShort(short value) {
        return (short) (value + 1);
    }
}
