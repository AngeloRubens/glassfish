package org.glassfish.orb.baseline;

import jakarta.ejb.Remote;

/** The stateless remote view under test. */
@Remote
public interface Greeter {

    String greet(String name, int times);

    Values.Node echoNode(Values.Node node);

    Values.Shared echoShared(Values.Shared shared);

    Values.WithTransient echoTransient(Values.WithTransient value);

    String echoLarge(String payload);

    void refuse();

    void explode();
}
