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
package org.grouplens.grapht.graph;

import org.grouplens.grapht.BindingFunctionBuilder;
import org.grouplens.grapht.BindingFunctionBuilder.RuleSet;
import org.grouplens.grapht.annotation.AnnotationBuilder;
import org.grouplens.grapht.solver.DefaultDesireBindingFunction;
import org.grouplens.grapht.solver.DependencySolver;
import org.grouplens.grapht.solver.DesireChain;
import org.grouplens.grapht.spi.CachePolicy;
import org.grouplens.grapht.spi.CachedSatisfaction;
import org.grouplens.grapht.spi.InjectSPI;
import org.grouplens.grapht.spi.reflect.InstanceSatisfaction;
import org.grouplens.grapht.spi.reflect.ReflectionInjectSPI;
import org.grouplens.grapht.spi.reflect.types.NamedType;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import javax.inject.Named;
import java.io.*;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;

public class SerializationTest {
    private static File GRAPH_FILE = new File("graph.dump");
    
    @Test
    public void testEmptyGraph() throws Exception {
        DAGNode<CachedSatisfaction, DesireChain> g =
                DAGNode.singleton(DependencySolver.ROOT_SATISFACTION);
        write(g);
        DAGNode<CachedSatisfaction, DesireChain> read = read();

        assertThat(read.getReachableNodes(),
                   contains(read));
    }
    
    @Test
    public void testSharedNodesGraph() throws Exception {
        InjectSPI spi = new ReflectionInjectSPI();
        CachedSatisfaction s1 = new CachedSatisfaction(spi.satisfy(Object.class), CachePolicy.NEW_INSTANCE);
        CachedSatisfaction s2 = new CachedSatisfaction(spi.satisfy(Object.class), CachePolicy.MEMOIZE);

        DAGNode<CachedSatisfaction, String> n2 = DAGNode.singleton(s2);
        DAGNodeBuilder<CachedSatisfaction, String> bld = DAGNode.newBuilder(s1);
        bld.addEdge(n2, "wombat");
        bld.addEdge(n2, "foobar");
        write(bld.build());
        DAGNode<Object, Object> read = read();
        
        Assert.assertEquals(2, read.getReachableNodes().size());
        assertThat(read.getOutgoingEdges(),
                   hasSize(2));
    }
    
    @Test
    public void testDependencySolverSerialization() throws Exception {
        BindingFunctionBuilder b = new BindingFunctionBuilder();
        b.getRootContext().bind(String.class).withQualifier(new AnnotationBuilder<Named>(Named.class).set("value", "unused").build()).to("shouldn't see this"); // extra binding to make sure it's skipped
        b.getRootContext().bind(String.class).withQualifier(new AnnotationBuilder<Named>(Named.class).set("value", "test1").build()).to("hello world");

        DependencySolver solver = DependencySolver.newBuilder()
                                                  .addBindingFunction(b.build(RuleSet.EXPLICIT))
                                                  .addBindingFunction(b.build(RuleSet.INTERMEDIATE_TYPES))
                                                  .addBindingFunction(b.build(RuleSet.SUPER_TYPES))
                                                  .addBindingFunction(new DefaultDesireBindingFunction(b.getSPI()))
                                                  .build();
        solver.resolve(b.getSPI().desire(null, NamedType.class, false));
        
        DAGNode<CachedSatisfaction,DesireChain> g = solver.getGraph();
        write(g);
        DAGNode<CachedSatisfaction, DesireChain> root = read();
        
        Assert.assertEquals(1, root.getOutgoingEdges().size());
        DAGEdge<CachedSatisfaction, DesireChain> rootEdge = root.getOutgoingEdges().iterator().next();
        DAGNode<CachedSatisfaction, DesireChain> namedType = rootEdge.getTail();
        
        Assert.assertEquals(NamedType.class, namedType.getLabel().getSatisfaction().getErasedType());
        Assert.assertEquals(NamedType.class, rootEdge.getLabel().getInitialDesire().getDesiredType());
        Assert.assertEquals(rootEdge.getLabel().getInitialDesire().getSatisfaction(), namedType.getLabel().getSatisfaction());
        Assert.assertNull(rootEdge.getLabel().getInitialDesire().getInjectionPoint().getAttributes().getQualifier());
        Assert.assertTrue(rootEdge.getLabel().getInitialDesire().getInjectionPoint().getAttributes().getAttributes().isEmpty());
        
        Assert.assertEquals(1, namedType.getOutgoingEdges().size());
        DAGEdge<CachedSatisfaction, DesireChain> nameEdge = namedType.getOutgoingEdges().iterator().next();
        DAGNode<CachedSatisfaction, DesireChain> string = nameEdge.getTail();
        
        Assert.assertEquals(String.class, string.getLabel().getSatisfaction().getErasedType());
        Assert.assertEquals(String.class, nameEdge.getLabel().getInitialDesire().getDesiredType());
        Assert.assertEquals(AnnotationBuilder.of(Named.class).setValue("test1").build(), nameEdge.getLabel().getInitialDesire().getInjectionPoint().getAttributes().getQualifier());
        Assert.assertTrue(nameEdge.getLabel().getInitialDesire().getInjectionPoint().getAttributes().getAttributes().isEmpty());
        
        Assert.assertTrue(string.getLabel().getSatisfaction() instanceof InstanceSatisfaction);
        Assert.assertEquals("hello world", ((InstanceSatisfaction) string.getLabel().getSatisfaction()).getInstance());
    }
    
    @After
    public void cleanup() throws Exception {
        GRAPH_FILE.delete();
    }
    
    private <V, E> void write(DAGNode<V, E> g) throws IOException {
        ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(GRAPH_FILE));
        out.writeObject(g);
        out.flush();
        out.close();
    }
    
    private <V,E> DAGNode<V,E> read() throws IOException, ClassNotFoundException {
        ObjectInputStream in = new ObjectInputStream(new FileInputStream(GRAPH_FILE));
        try {
            DAGNode<V,E> g = (DAGNode<V,E>) in.readObject();
            return g;
        } finally {
            in.close();
        }
    }
}
