package org.glassfish.orb.baseline;

import jakarta.ejb.Remote;

/** The stateful remote view under test. */
@Remote
public interface Counter {

    int increment();

    int value();
}
