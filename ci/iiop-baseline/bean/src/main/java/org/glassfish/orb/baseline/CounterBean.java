package org.glassfish.orb.baseline;

import jakarta.ejb.Stateful;

@Stateful
public class CounterBean implements Counter {

    private int count;

    @Override
    public int increment() {
        return ++count;
    }

    @Override
    public int value() {
        return count;
    }
}
