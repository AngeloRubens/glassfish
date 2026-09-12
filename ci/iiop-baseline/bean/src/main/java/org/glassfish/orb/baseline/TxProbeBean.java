package org.glassfish.orb.baseline;

import jakarta.annotation.Resource;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * Answers from inside whatever transaction the container gave it.
 */
@Stateless
public class TxProbeBean implements TxProbe {

    @Resource
    private TransactionSynchronizationRegistry registry;

    /**
     * {@inheritDoc}
     *
     * <p>SUPPORTS rather than REQUIRED on purpose: REQUIRED would start a
     * transaction when none arrived, and the answer would be the same either
     * way. With SUPPORTS, a null key means no transaction reached this bean,
     * which is the thing worth being able to observe.
     */
    @Override
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public String transactionKey() {
        return key();
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.MANDATORY)
    public void markRollbackOnly() {
        registry.setRollbackOnly();
    }

    // Every one of these returns the same thing - the transaction it is
    // running in - so any difference between them is the attribute doing its
    // work, not the method.

    @Override
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public String required() {
        return key();
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public String requiresNew() {
        return key();
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.MANDATORY)
    public String mandatory() {
        return key();
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public String supports() {
        return key();
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public String notSupported() {
        return key();
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.NEVER)
    public String never() {
        return key();
    }

    private String key() {
        Object key = registry == null ? null : registry.getTransactionKey();
        return key == null ? null : key.toString();
    }
}
