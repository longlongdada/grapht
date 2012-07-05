/*
 * Grapht, an open source dependency injector.
 * Copyright 2010-2012 Regents of the University of Minnesota and contributors
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.grouplens.grapht;

import java.lang.annotation.Annotation;

import org.grouplens.grapht.BindingFunctionBuilder.RuleSet;
import org.grouplens.grapht.solver.CachePolicy;
import org.grouplens.grapht.solver.DefaultDesireBindingFunction;
import org.grouplens.grapht.solver.DefaultInjector;
import org.grouplens.grapht.spi.reflect.ReflectionInjectSPI;

/**
 * <p>
 * InjectorBuilder is a Builder implementation that is capable of creating a
 * simple {@link Injector}. Additionally, it is root {@link Context} to make
 * configuring the built Injector as easy as possible. Injectors created by
 * InjectorBuilder instances memoize their created objects.
 * </p>
 * <p>
 * Internally, it uses an {@link InjectorConfigurationBuilder} to accumulate
 * bind rules, a {@link DefaultInjector} to resolve dependencies, and the
 * {@link ReflectionInjectSPI} to access dependency information.
 * 
 * @author Michael Ludwig <mludwig@cs.umn.edu>
 */
public class InjectorBuilder implements Context {
    private final BindingFunctionBuilder builder;
    private CachePolicy cachePolicy;

    /**
     * Create a new InjectorBuilder that automatically applies the given Modules
     * via {@link #applyModule(Module)}. Additional Modules can be applied later
     * as well. Configuration via the {@link Context} interface is also possible
     * (and recommended if Modules aren't used) before calling {@link #build()}.
     * 
     * @param modules Any modules to apply immediately
     */
    public InjectorBuilder(Module... modules) {
        builder = new BindingFunctionBuilder();
        for (Module m: modules) {
            applyModule(m);
        }
        cachePolicy = CachePolicy.MEMOIZE;
    }
    
    /**
     * Set the default cache policy used by injectors created by this builder.
     * 
     * @param policy The default policy
     * @return This builder
     * @throws NullPointerException if policy is null
     * @throws IllegalArgumentException if policy is NO_PREFERENCE
     */
    public InjectorBuilder setDefaultCachePolicy(CachePolicy policy) {
        if (policy.equals(CachePolicy.NO_PREFERENCE)) {
            throw new IllegalArgumentException("Cannot be NO_PREFERENCE");
        }
        
        cachePolicy = policy;
        return this;
    }
    
    @Override
    public <T> Binding<T> bind(Class<T> type) {
        return builder.getRootContext().bind(type);
    }
    
    @Override
    public void bind(Class<? extends Annotation> param, Object value) {
        builder.getRootContext().bind(param, value);
    }

    @Override
    public Context in(Class<?> type) {
        return builder.getRootContext().in(type);
    }

    @Override
    public Context in(Class<? extends Annotation> qualifier, Class<?> type) {
        return builder.getRootContext().in(qualifier, type);
    }
    
    @Override
    public Context in(Annotation annot, Class<?> type) {
        return builder.getRootContext().in(annot, type);
    }

    /**
     * Apply a module to the root context of this InjectorBuilder (i.e.
     * {@link Module#bind(Context)}).
     * 
     * @param module The module to apply
     * @return This InjectorBuilder
     */
    public InjectorBuilder applyModule(Module module) {
        builder.applyModule(module);
        return this;
    }

    public Injector build() {
        return new DefaultInjector(builder.getSPI(),
                                   cachePolicy,
                                   100,
                                   builder.getFunction(RuleSet.EXPLICIT),
                                   builder.getFunction(RuleSet.INTERMEDIATE_TYPES),
                                   builder.getFunction(RuleSet.SUPER_TYPES),
                                   new DefaultDesireBindingFunction(builder.getSPI()));
    }
}
