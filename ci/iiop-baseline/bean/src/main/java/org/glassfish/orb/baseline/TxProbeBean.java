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
        Object key = registry == null ? null : registry.getTransactionKey();
        return key == null ? null : key.toString();
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.MANDATORY)
    public void markRollbackOnly() {
        registry.setRollbackOnly();
    }
}
