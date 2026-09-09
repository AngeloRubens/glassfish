package org.glassfish.orb.baseline;

import jakarta.ejb.Stateless;

@Stateless
public class GreeterBean implements Greeter {

    @Override
    public String greet(String name, int times) {
        return (name + ' ').repeat(times).trim();
    }

    @Override
    public Values.Node echoNode(Values.Node node) {
        return node;
    }

    @Override
    public Values.Shared echoShared(Values.Shared shared) {
        return shared;
    }

    @Override
    public Values.WithTransient echoTransient(Values.WithTransient value) {
        return value;
    }

    @Override
    public String echoLarge(String payload) {
        return payload;
    }

    @Override
    public void refuse() {
        throw new Values.Refused("not today", "E_CLOSED",
                new IllegalStateException("underlying cause"));
    }
}
