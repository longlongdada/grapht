/*
 * LensKit, an open source recommender systems toolkit.
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
package org.grouplens.inject.resolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.grouplens.inject.graph.Edge;
import org.grouplens.inject.graph.Graph;
import org.grouplens.inject.graph.Node;
import org.grouplens.inject.spi.BindRule;
import org.grouplens.inject.spi.ContextMatcher;
import org.grouplens.inject.spi.Desire;
import org.grouplens.inject.spi.Qualifier;
import org.grouplens.inject.spi.Satisfaction;

import com.google.common.collect.Ordering;

/**
 * DependencyResolver is a utility for resolving a Desire into its dependency
 * tree (although this tree is stored as a {@link Graph}). This is used by the
 * {@link DefaultResolver} to build the initial tree for each requested desire,
 * before merging them into an actual graph that shares dependencies when
 * possible.
 * 
 * @author Michael Ludwig <mludwig@cs.umn.edu>
 */
class DependencyResolver {
    private final int maxDepth;
    private final Map<ContextChain, Collection<? extends BindRule>> bindRules;
    
    public DependencyResolver(Map<ContextChain, Collection<? extends BindRule>> bindRules, int maxDepth) {
        this.bindRules = bindRules;
        this.maxDepth = maxDepth;
    }
    
    public Graph<Satisfaction, List<Desire>> resolve(Desire desire) {
        Graph<Satisfaction, List<Desire>> graph = new Graph<Satisfaction, List<Desire>>();
        Node<Satisfaction> root = new Node<Satisfaction>(null); // set label to null to identify it
        resolveFully(desire, root, graph, new ArrayList<Pair<Satisfaction, Qualifier>>());
        return graph;
    }
    
    private void resolveFully(Desire desire, Node<Satisfaction> parent, Graph<Satisfaction, List<Desire>> graph, 
                              List<Pair<Satisfaction, Qualifier>> context) {
        // check context depth against max to detect likely dependency cycles
        if (context.size() > maxDepth)
            throw new ResolverException("Dependencies reached max depth of " + maxDepth + ", there is likely a dependency cycle");
        
        // resolve the current node
        Pair<Satisfaction, List<Desire>> resolved = resolve(desire, bindRules, context);
        Node<Satisfaction> newNode = new Node<Satisfaction>(resolved.getLeft());
        
        // add the node to the graph, and connect it with its parent
        graph.addNode(newNode);
        graph.addEdge(new Edge<Satisfaction, List<Desire>>(parent, newNode, resolved.getRight()));
        
        // update the context
        List<Pair<Satisfaction, Qualifier>> newContext = new ArrayList<Pair<Satisfaction, Qualifier>>(context);
        newContext.add(Pair.of(resolved.getLeft(), desire.getQualifier()));
        
        List<? extends Desire> dependencies = resolved.getLeft().getDependencies();
        for (Desire d: dependencies) {
            // complete the sub graph for the given desire
            // - the call to resolveFully() is responsible for adding the dependency edges
            //   so we don't need to process the returned node
            resolveFully(d, newNode, graph, newContext);
        }
    }
    
    private Pair<Satisfaction, List<Desire>> resolve(Desire desire, Map<ContextChain, Collection<? extends BindRule>> bindRules, List<Pair<Satisfaction, Qualifier>> context) {
        // bind rules can only be used once when satisfying a desire,
        // this set will record all used bind rules so they are no longer considered
        Set<BindRule> appliedRules = new HashSet<BindRule>();
        
        List<Desire> desireChain = new ArrayList<Desire>();
        Desire currentDesire = desire;
        while(true) {
            // remember the current desire in the chain of followed desires
            desireChain.add(currentDesire);
            
            // collect all bind rules that apply to this desire
            List<Pair<ContextChain, BindRule>> validRules = new ArrayList<Pair<ContextChain, BindRule>>();
            for (ContextChain chain: bindRules.keySet()) {
                if (chain.matches(context)) {
                    // the context applies to the current context, so go through all
                    // bind rules within it and record those that match the desire
                    for (BindRule br: bindRules.get(chain)) {
                        if (br.matches(currentDesire) && !appliedRules.contains(br)) {
                            validRules.add(Pair.of(chain, br));
                        }
                    }
                }
            }
            
            if (!validRules.isEmpty()) {
                // we have a bind rule to apply
                Comparator<Pair<ContextChain, BindRule>> ordering = Ordering.from(new ContextClosenessComparator(context))
                                                                            .compound(new ContextLengthComparator())
                                                                            .compound(new TypeDeltaComparator(context))
                                                                            .compound(new BindRuleComparator(currentDesire));
                Collections.sort(validRules, ordering);

                if (validRules.size() > 1) {
                    // must check if the 2nd bind rule is equivalent in order to the first
                    if (ordering.compare(validRules.get(0), validRules.get(1)) == 0) {
                        // TODO REVIEW: return more information in the message?
                        throw new ResolverException("Too many choices for desire: " + currentDesire);
                    }
                }

                // apply the bind rule to get a new desire
                BindRule selectedRule = validRules.get(0).getRight();
                appliedRules.add(selectedRule);
                currentDesire = selectedRule.apply(currentDesire);
                
                // possibly terminate if the bind rule terminates
                if (selectedRule.terminatesChain() && currentDesire.isInstantiable()) {
                    desireChain.add(currentDesire);
                    return Pair.of(currentDesire.getSatisfaction(), desireChain);
                }
            } else {
                // attempt to use the default desire, or terminate if we've found
                // a satisfiable desire
                Desire defaultDesire = currentDesire.getDefaultDesire();
                if (defaultDesire == null || desireChain.contains(defaultDesire)) {
                    // we don't use the default if there wasn't one, or using the
                    // default would create a cycle of desires
                    if (currentDesire.isInstantiable()) {
                        // the desire can be converted to a node, so we're done
                        return Pair.of(currentDesire.getSatisfaction(), desireChain);
                    } else {
                        // no more rules and we can't make a node
                        throw new ResolverException("Unable to satisfy desire: " + currentDesire + ", root desire: " + desire);
                    }
                } else {
                    // continue with the default desire
                    currentDesire = defaultDesire;
                }
            }
        }
    }

    /*
     * A Comparator that orders Pair<ContextChain, BindRule> based on the BindRule/Desire
     * implementation that orders BindRules.
     */
    private static class BindRuleComparator implements Comparator<Pair<ContextChain, BindRule>> {
        private final Desire desire;
        
        public BindRuleComparator(Desire desire) {
            this.desire = desire;
        }
        
        @Override
        public int compare(Pair<ContextChain, BindRule> o1, Pair<ContextChain, BindRule> o2) {
            Comparator<BindRule> ruleComparator = desire.ruleComparator();
            return ruleComparator.compare(o1.getRight(), o2.getRight());
        }
    }
    
    /*
     * A Comparator that compares rules based on the "type" delta of the matchers
     * with the nodes in the current context.
     * 
     * This comparator assumes that both context chains are the same length,
     * and that they match the exact same nodes in the context.
     */
    private static class TypeDeltaComparator implements Comparator<Pair<ContextChain, BindRule>> {
        private final List<Pair<Satisfaction, Qualifier>> context;
        
        public TypeDeltaComparator(List<Pair<Satisfaction, Qualifier>> context) {
            this.context = context;
        }
        
        @Override
        public int compare(Pair<ContextChain, BindRule> o1, Pair<ContextChain, BindRule> o2) {
            int lastIndex1 = o1.getLeft().getContexts().size() - 1;
            int lastIndex2 = o2.getLeft().getContexts().size() - 1;
            
            int matcher = 0; // measured from the last index
            for (int i = context.size() - 1; i >= 0; i--) {
                if (matcher > lastIndex1 || matcher > lastIndex2) {
                    // we've reached the end of one of the matcher chains
                    break;
                }
                
                Pair<Satisfaction, Qualifier> currentNode = context.get(i);
                ContextMatcher m1 = o1.getLeft().getContexts().get(lastIndex1 - matcher);
                ContextMatcher m2 = o2.getLeft().getContexts().get(lastIndex2 - matcher);
                
                boolean match1 = m1.matches(currentNode);
                boolean match2 = m2.matches(currentNode);
                
                // if the chains match the same nodes, they should both match
                // or neither match
                assert match1 == match2;
                
                if (match1 && match2) {
                    // the chains apply to this node so we need to compare them
                    int cmp = currentNode.getLeft().contextComparator(currentNode.getRight()).compare(m1, m2);
                    if (cmp != 0) {
                        // one chain finally has a type delta difference, so the
                        // comparison of the chain equals the matcher comparison
                        return cmp;
                    } else {
                        // otherwise the matchers are equal so move to the next matcher
                        matcher++;
                    }
                }
            }
            
            // if we've gotten here, all matchers in each chain have the
            // same type delta to their respective matching nodes
            return 0;
        }
    }
    
    /*
     * A Comparator that compares rules based on how long the matching contexts are.
     */
    private static class ContextLengthComparator implements Comparator<Pair<ContextChain, BindRule>> {
        @Override
        public int compare(Pair<ContextChain, BindRule> o1, Pair<ContextChain, BindRule> o2) {
            int l1 = o1.getLeft().getContexts().size();
            int l2 = o2.getLeft().getContexts().size();
            // select longer contexts over shorter (i.e. longer < shorter)
            return l2 - l1;
        }
    }
    
    /*
     * A Comparator that compares rules based on how close a context matcher chain is to the
     * end of the current context.
     */
    private static class ContextClosenessComparator implements Comparator<Pair<ContextChain, BindRule>> {
        private final List<Pair<Satisfaction, Qualifier>> context;
        
        public ContextClosenessComparator(List<Pair<Satisfaction, Qualifier>> context) {
            this.context = context;
        }
        
        @Override
        public int compare(Pair<ContextChain, BindRule> o1, Pair<ContextChain, BindRule> o2) {
            int lastIndex1 = o1.getLeft().getContexts().size() - 1;
            int lastIndex2 = o2.getLeft().getContexts().size() - 1;
            
            int matcher = 0; // measured from the last index
            for (int i = context.size() - 1; i >= 0; i--) {
                if (matcher > lastIndex1 || matcher > lastIndex2) {
                    // we've reached the end of one of the matcher chains
                    break;
                }
                
                boolean match1 = o1.getLeft().getContexts().get(lastIndex1 - matcher).matches(context.get(i));
                boolean match2 = o2.getLeft().getContexts().get(lastIndex2 - matcher).matches(context.get(i));
                
                if (match1 && match2) {
                    // both chains match this context element, so go to the next matcher
                    matcher++;
                } else if (match1 && !match2) {
                    // first chain is closest match
                    return -1;
                } else if (!match1 && match2) {
                    // second chain is the closest match
                    return 1;
                } // else not matched in the context yet, so move up the context
            }
            
            // if we've made it here, both chains were equal up to the shortest chain,
            // or at least one of the chains was empty (that part is a little strange,
            // but we'll get correct results when we sort by context chain length next).
            return 0;
        }
    }
}