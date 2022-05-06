//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.webapp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jetty.util.TopologicalSort;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Relative Fragment Ordering
 * <p>Uses a {@link TopologicalSort} to order the fragments.</p>
 */
public class RelativeOrdering implements Ordering
{
    private static final Logger LOG = Log.getLogger(RelativeOrdering.class);

    protected MetaData _metaData;

    public RelativeOrdering(MetaData metaData)
    {
        _metaData = metaData;
    }

    @Override
    public List<Resource> order(List<Resource> jars)
    {
        TopologicalSort<Resource> sort = new TopologicalSort<>();
        List<Resource> sorted = new ArrayList<>(jars);
        Set<Resource> others = new HashSet<>();
        Set<Resource> beforeOthers = new HashSet<>();
        Set<Resource> afterOthers = new HashSet<>();

        // Pass 1: split the jars into 'before others', 'others' or 'after others'
        for (Resource jar : jars)
        {
            FragmentDescriptor fragment = _metaData.getFragment(jar);

            if (fragment == null)
                others.add(jar);
            else
            {
                switch (fragment.getOtherType())
                {
                    case None:
                        others.add(jar);
                        break;
                    case Before:
                        beforeOthers.add(jar);
                        break;
                    case After:
                        afterOthers.add(jar);
                        break;
                }
            }
        }

        // Pass 2: Add sort dependencies for each jar
        Set<Resource> referenced = new HashSet<>();
        for (Resource jar : jars)
        {
            FragmentDescriptor fragment = _metaData.getFragment(jar);

            if (fragment != null)
            {
                // Add each explicit 'after' ordering as a sort dependency
                // and remember that the dependency has been referenced.
                for (String name : fragment.getAfters())
                {
                    Resource after = _metaData.getJarForFragment(name);
                    if (after == null) {
                        // do not add the jar dependency if it refers to a webapp not yet
                        // added (i.e. MetaData.addFragment has not been called yet for that
                        // webapp, or it does not exist).
                        LOG.debug("Jar '{}' depends on after '{}' which does not exists yet", jar.getName(), name);
                    } else {
                        sort.addDependency(jar, after);
                    }
                    referenced.add(after);
                }

                // Add each explicit 'before' ordering as a sort dependency
                // and remember that the dependency has been referenced.
                for (String name : fragment.getBefores())
                {
                    Resource before = _metaData.getJarForFragment(name);
                    if (before == null) {
                        // do not add the jar dependency if it refers to a webapp not yet
                        // added (i.e. MetaData.addFragment has not been called yet for that
                        // webapp, or it does not exist).
                        LOG.debug("Jar '{}' depends on before '{}' which does not exists yet", jar.getName(), name);
                    } else {
                        sort.addDependency(before, jar);
                    }
                    referenced.add(before);
                }

                // handle the others
                switch (fragment.getOtherType())
                {
                    case None:
                        break;
                    case Before:
                        // Add a dependency on this jar from all
                        // jars in the 'others' and 'after others' sets, but
                        // exclude any jars we have already explicitly
                        // referenced above.
                        Consumer<Resource> addBefore = other ->
                        {
                            if (!referenced.contains(other))
                                sort.addDependency(other, jar);
                        };
                        others.forEach(addBefore);
                        afterOthers.forEach(addBefore);
                        break;

                    case After:
                        // Add a dependency from this jar to all
                        // jars in the 'before others' and 'others' sets, but
                        // exclude any jars we have already explicitly
                        // referenced above.
                        Consumer<Resource> addAfter = other ->
                        {
                            if (!referenced.contains(other))
                                sort.addDependency(jar, other);
                        };
                        beforeOthers.forEach(addAfter);
                        others.forEach(addAfter);
                        break;
                }
            }
            referenced.clear();
        }

        // sort the jars according to the added dependencies
        sort.sort(sorted);

        return sorted;
    }
}
