/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.orb.http.glassfish;



import com.sun.ejb.containers.EjbContainerUtil;
import com.sun.ejb.containers.EjbContainerUtilImpl;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.rmi.Remote;

import org.glassfish.enterprise.iiop.spi.EjbContainerFacade;
import org.glassfish.orb.http.protocol.EjbKey;
import org.glassfish.orb.http.server.ContainerBridge;
import org.jvnet.hk2.annotations.Service;

/**
 * Binds the transport's dispatch to this container.
 *
 * <p>Almost nothing here is translation. {@code EjbContainerFacade} already
 * says what an arriving remote invocation needs - resolve a key to a target,
 * release it afterwards, and hand over the deployment's class loader - and
 * {@code BaseContainer} implements it with a comment recording that it is
 * called "from the ProtocolManager when a remote invocation arrives". This
 * class is a second such protocol manager.
 *
 * <p>Two things do need care.
 *
 * <p>The container addresses beans by {@code ejbId}, and this transport's
 * paths carry names; {@link EjbNameIndex} closes that gap.
 *
 * <p>And {@code getTargetObject} wants the name of the <em>generated</em>
 * remote interface, not the business interface the client named:
 * {@code BaseContainer} keys its view table by the generated type. Passing the
 * business interface through unchanged would miss the table and look, from the
 * client's side, exactly like a bean that does not exist.
 */
@Service
@Singleton
public class GlassFishContainerBridge implements ContainerBridge {

    @Inject
    private EjbNameIndex index;

    /**
     * The facade that produced the current target.
     *
     * <p>{@link #releaseTargetObject} is handed the target and not the key, and
     * the release has to reach the same container that produced it. A thread
     * local is faithful rather than expedient: the pairing it stands in for -
     * {@code externalPreInvoke} and {@code externalPostInvoke} - is itself
     * thread-scoped, because what it restores is the context class loader.
     */
    private final ThreadLocal<EjbContainerFacade> currentFacade = new ThreadLocal<>();

    @Override
    public EjbKey resolve(String appName, String moduleName, String distinctName,
                          String beanName, byte[] sessionId) throws NoSuchTargetException {
        Long ejbId = index.lookup(appName, moduleName, beanName);
        if (ejbId == null) {
            throw new NoSuchTargetException("no bean " + beanName
                    + " in " + appName + '/' + moduleName);
        }
        return sessionId == null ? EjbKey.home(ejbId) : new EjbKey(ejbId, sessionId);
    }

    @Override
    public Object getTargetObject(EjbKey key, String viewClassName) throws NoSuchTargetException {
        EjbContainerFacade facade = facade(key);
        String generatedView = generatedViewName(viewClassName);

        Remote target = facade.getTargetObject(key.instanceKey(), generatedView);
        if (target == null) {
            // Rare, and the container's own comment says so: for stateful and
            // entity beans this can be null when the instance has gone.
            throw new NoSuchTargetException("no live instance for " + key);
        }
        currentFacade.set(facade);
        return target;
    }

    @Override
    public void releaseTargetObject(Object target) {
        EjbContainerFacade facade = currentFacade.get();
        currentFacade.remove();
        if (facade != null && target instanceof Remote remote) {
            facade.releaseTargetObject(remote);
        }
    }

    @Override
    public ClassLoader classLoader(EjbKey key) {
        ClassLoader loader = util().getClassLoader(key.ejbId());
        return loader != null ? loader : getClass().getClassLoader();
    }

    @Override
    public byte[] createSession(String appName, String moduleName, String distinctName,
                                String beanName) throws NoSuchTargetException {
        // Not implemented, and not stubbed as a success.
        //
        // A stateful session is created through the bean's home:
        // EJBHomeImpl.createEJBObjectImpl delegates to the container's
        // protected createEJBObjectImpl(). For the EJB 2.x home view that is
        // reachable by invoking create() on the home target. For the EJB 3
        // business view it is not: GenericEJBHome carries only the
        // asynchronous-result operations, and the session is created elsewhere
        // in the lookup path. Until that route is established, answering
        // anything here would hand the client a session id naming nothing.
        throw new NoSuchTargetException("stateful session creation is not yet wired to this container");
    }

    @Override
    public void removeSession(EjbKey key) throws NoSuchTargetException {
        throw new NoSuchTargetException("stateful session removal is not yet wired to this container");
    }

    /**
     * @param viewClassName the business interface the client named, or null for
     *                      the remote home view
     * @return the generated interface name the container's view table is keyed
     *         by, or null to select the home view
     */
    private static String generatedViewName(String viewClassName) throws NoSuchTargetException {
        if (viewClassName == null) {
            return null;
        }
        try {
            return com.sun.ejb.codegen.RemoteGenerator.getGeneratedRemoteIntfName(viewClassName);
        } catch (RuntimeException e) {
            throw new NoSuchTargetException("cannot derive the generated interface for "
                    + viewClassName + ": " + e);
        }
    }

    private EjbContainerFacade facade(EjbKey key) throws NoSuchTargetException {
        EjbContainerFacade facade = util().getContainer(key.ejbId());
        if (facade == null) {
            // The index named a bean the container no longer has - an
            // application undeployed between the lookup and the call.
            index.refresh();
            throw new NoSuchTargetException("no container for ejbId " + key.ejbId());
        }
        return facade;
    }

    private static EjbContainerUtil util() {
        return EjbContainerUtilImpl.getInstance();
    }
}
