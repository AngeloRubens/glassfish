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
