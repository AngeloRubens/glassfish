package org.glassfish.orb.baseline;

import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.InitialContext;

/**
 * The same baseline over HTTP, and the whole argument in one file.
 *
 * <p>Compare this with {@link IiopBaseline}. The environment differs by two
 * entries; the assertions are not merely equivalent but literally the same
 * code, in {@link Baseline}. If this passes, the claim that an existing client
 * migrates by changing the factory and the URL is not an argument from the
 * shape of the code - it is a thing that ran.
 */
public final class HttpBaseline {

    private HttpBaseline() {
    }

    public static void main(String[] args) throws Exception {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY,
                "org.glassfish.orb.http.client.HttpInitialContextFactory");
        environment.put(Context.PROVIDER_URL,
                System.getProperty("endpoint", "http://localhost:8080/glassfish-services"));

        InitialContext context = new InitialContext(environment);
        try {
            System.exit(Baseline.run(context, "HTTP") == 0 ? 0 : 1);
        } finally {
            context.close();
        }
    }
}
