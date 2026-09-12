package org.glassfish.orb.baseline;

import jakarta.annotation.Resource;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.transaction.TransactionSynchronizationRegistry;

import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.InitialContext;

/**
 * Calls the second server over the same transport a client would use.
 * <p>
 * Nothing here is transport-aware beyond two JNDI properties. That is the
 * claim being made: a bean reaches another server by naming a factory and a
 * URL, and the transaction it is already in goes with the call because the
 * client joins it - not because this code arranges anything.
 */
@Stateless
public class RelayBean implements Relay {

    /**
     * Where the second server is. A property rather than a constant so the
     * same application can be pointed anywhere.
     */
    private static final String FAR_ENDPOINT = System.getProperty(
            "baseline.far.endpoint", "http://localhost:9080/glassfish-services");

    private static final String FAR_NAME =
            "java:global/orb-baseline-bean/TxProbeBean!" + TxProbe.class.getName();

    @Resource
    private TransactionSynchronizationRegistry registry;

    @Override
    @TransactionAttribute(TransactionAttributeType.MANDATORY)
    public String bothTransactions() {
        try {
            return here() + '|' + far().transactionKey();
        } catch (Throwable t) {
            return error(t);
        }
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.MANDATORY)
    public String markRollbackOnlyRemotely() {
        try {
            // The decision is taken two servers away from the client that will
            // ask for the commit.
            far().markRollbackOnly();
            return "ok";
        } catch (Throwable t) {
            return error(t);
        }
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public String remoteTransaction() {
        try {
            String key = far().transactionKey();
            return key == null ? "none" : key;
        } catch (Throwable t) {
            return error(t);
        }
    }

    /**
     * Turns whatever went wrong into something that survives the trip.
     *
     * @param t what stopped the second hop
     * @return a single line naming the failure and its causes
     */
    private static String error(Throwable t) {
        StringBuilder line = new StringBuilder("ERROR ");
        for (Throwable current = t; current != null && line.length() < 900;
                current = current.getCause() == current ? null : current.getCause()) {
            line.append(current.getClass().getName()).append(": ")
                .append(String.valueOf(current.getMessage()).replace('\n', ' '))
                .append(" <- ");
        }
        return line.toString();
    }

    private String here() {
        Object key = registry == null ? null : registry.getTransactionKey();
        return key == null ? "none" : key.toString();
    }

    private TxProbe far() {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY,
                "org.glassfish.orb.http.client.HttpInitialContextFactory");
        environment.put(Context.PROVIDER_URL, FAR_ENDPOINT);
        try {
            InitialContext context = new InitialContext(environment);
            try {
                return (TxProbe) context.lookup(FAR_NAME);
            } finally {
                context.close();
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot reach " + FAR_ENDPOINT, e);
        }
    }
}
