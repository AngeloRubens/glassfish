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


import com.sun.enterprise.deployment.Application;
import com.sun.enterprise.deployment.EjbBundleDescriptor;
import com.sun.enterprise.deployment.EjbDescriptor;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.glassfish.internal.data.ApplicationInfo;
import org.glassfish.internal.data.ApplicationRegistry;
import org.jvnet.hk2.annotations.Service;

/**
 * Resolves a bean's four-part name to the id the container knows it by.
 *
 * <p>The container is addressed entirely by {@code ejbId}:
 * {@code EjbContainerUtil} offers {@code getContainer(long)},
 * {@code getDescriptor(long)} and {@code getClassLoader(long)}, and nothing
 * keyed by name. The IIOP path never needs the mapping, because the id travels
 * inside the object key of a reference the server itself minted. A client that
 * builds a locator from a name rather than from a lookup holds no such
 * reference, so the mapping has to exist somewhere - here.
 *
 * <p>Built lazily from {@link ApplicationRegistry} rather than from deployment
 * events. Events are the more obvious mechanism and the wrong one: their
 * ordering relative to the first invocation is not guaranteed, and an index
 * that is merely usually populated fails as a 404 indistinguishable from a
 * misspelled bean name. A miss here refreshes and retries, so the worst case
 * is a slow first call rather than a wrong answer.
 */
@Service
@Singleton
public class EjbNameIndex {

    @Inject
    private ApplicationRegistry applications;

    private final Map<String, Long> byName = new ConcurrentHashMap<>();

    /**
     * @param appName the application name
     * @param moduleName the module within it, or null if the caller does not know it
     * @param beanName the bean's ejb-name
     * @return the container's id for that bean, or null if there is no such bean
     */
    public Long lookup(String appName, String moduleName, String beanName) {
        String key = key(appName, moduleName, beanName);
        Long id = byName.get(key);
        if (id != null) {
            return id;
        }
        refresh();
        return byName.get(key);
    }

    /** Rebuilds the index from what is deployed right now. */
    public void refresh() {
        Map<String, Long> rebuilt = new ConcurrentHashMap<>();
        for (String name : applications.getAllApplicationNames()) {
            ApplicationInfo info = applications.get(name);
            if (info == null) {
                continue;
            }
            Application application = info.getMetaData(Application.class);
            if (application == null) {
                // Not a Jakarta EE application; it has no EJBs to index.
                continue;
            }
            index(application, rebuilt);
        }
        byName.clear();
        byName.putAll(rebuilt);
    }

    private void index(Application application, Map<String, Long> into) {
        for (EjbBundleDescriptor bundle : application.getBundleDescriptors(EjbBundleDescriptor.class)) {
            String moduleName = bundle.getModuleDescriptor().getModuleName();
            for (EjbDescriptor ejb : bundle.getEjbs()) {
                into.put(key(application.getRegistrationName(), moduleName, ejb.getName()),
                        ejb.getUniqueId());
                // Also index without the module, so a client that knows only
                // the application and the bean is still served when the name is
                // unambiguous. putIfAbsent rather than put: on a collision the
                // ambiguous key should resolve to nothing in particular rather
                // than to whichever bean happened to be indexed last.
                into.putIfAbsent(key(application.getRegistrationName(), null, ejb.getName()),
                        ejb.getUniqueId());
            }
        }
    }

    private static String key(String appName, String moduleName, String beanName) {
        return appName + '/' + (moduleName == null ? "*" : moduleName) + '/' + beanName;
    }

    /** @return how many beans are currently indexed, for diagnostics */
    public int size() {
        return byName.size();
    }
}
