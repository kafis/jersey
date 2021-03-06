/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.server;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.List;

import javax.ws.rs.NameBinding;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.PostMatching;
import javax.ws.rs.core.Response;

import org.glassfish.jersey.internal.inject.AbstractModule;
import org.glassfish.jersey.internal.inject.Providers;
import org.glassfish.jersey.process.Inflector;
import org.glassfish.jersey.process.internal.AbstractChainableStage;
import org.glassfish.jersey.process.internal.PriorityComparator;
import org.glassfish.jersey.process.internal.ResponseProcessor;
import org.glassfish.jersey.process.internal.Stages;

import org.glassfish.hk2.Factory;
import org.glassfish.hk2.Services;

import org.jvnet.hk2.annotations.Inject;

import com.google.common.base.Predicate;

/**
 * Container filtering stage responsible for execution of request and response filters
 * on each request-response message exchange.
 *
 * @author Marek Potociar (marek.potociar at oracle.com)
 */
class ContainerFilteringStage extends AbstractChainableStage<ContainerRequest> {

    private static final Predicate<ContainerRequestFilter> PRE_MATCH_FILTER_PREDICATE = new Predicate<ContainerRequestFilter>() {
        @Override
        public boolean apply(final ContainerRequestFilter filter) {
            for (Annotation annotation : filter.getClass().getAnnotations()) {
                if (annotation instanceof PostMatching) {
                    return false;
                }
                for (Annotation metaAnnotation : annotation.getClass().getAnnotations()) {
                    if (metaAnnotation instanceof NameBinding) {
                        return false;
                    }
                }
            }

            return true;
        }
    };

    // TODO implement real support for filter named bindings
    private static final Predicate<ContainerRequestFilter> POST_MATCH_FILTER_PREDICATE = new Predicate<ContainerRequestFilter>() {
        @Override
        public boolean apply(final ContainerRequestFilter filter) {
            for (Annotation annotation : filter.getClass().getAnnotations()) {
                if (annotation instanceof PostMatching) {
                    return true;
                }
                for (Annotation metaAnnotation : annotation.getClass().getAnnotations()) {
                    if (metaAnnotation instanceof NameBinding) {
                        return true;
                    }
                }
            }

            return false;
        }
    };


    /**
     * Injectable container filtering stage builder.
     */
    static class Builder {

        @Inject
        Services services;

        @Inject
        private Factory<ResponseProcessor.RespondingContext<ContainerResponse>> respondingContextFactory;

        /**
         * Build a new container filtering stage specifying whether the built stage is
         * a pre-match filtering ({@code true}) stage or post-match ({@code false}).
         * <p>
         * A pre-match stage would search only for pre-match filters and would
         * NOT try to search for response filters or would NOT register any response
         * filtering stage in the {@link ResponseProcessor.RespondingContext responding context}.
         * </p>
         * <p>
         * A post-match stage would run all bound post-matching request filters
         * as well as also load all the applicable response filters and register
         * a response filtering stage in the responding context to run the response
         * filters.
         * </p>
         *
         * @param preMatch if {@code true} a pre-match filtering stage is built, otherwise
         *                 post-match stage is build.
         * @return new container filtering stage.
         */
        public ContainerFilteringStage build(boolean preMatch) {
            return new ContainerFilteringStage(respondingContextFactory, services, preMatch);
        }

    }

    private final Factory<ResponseProcessor.RespondingContext<ContainerResponse>> respondingContextFactory;
    private Services services;
    private final boolean preMatch;

    /**
     * Injection constructor.
     *
     * @param services                 HK2 services.
     * @param respondingContextFactory responding context factory.
     * @param preMatch                 if {@code true} a pre-match filtering stage is built, otherwise
     *                                 post-match stage is build.
     */
    private ContainerFilteringStage(
            Factory<ResponseProcessor.RespondingContext<ContainerResponse>> respondingContextFactory,
            Services services,
            boolean preMatch) {

        this.services = services;
        this.respondingContextFactory = respondingContextFactory;
        this.preMatch = preMatch;
    }

    @Override
    public Continuation<ContainerRequest> apply(ContainerRequest requestContext) {

        final List<ContainerResponseFilter> responseFilters = Providers.getAllProviders(services,
                ContainerResponseFilter.class,
                new PriorityComparator<ContainerResponseFilter>(PriorityComparator.Order.DESCENDING));
        if (!responseFilters.isEmpty()) {
            respondingContextFactory.get().push(new ResponseFilterStage(responseFilters));
        }

        Iterable<ContainerRequestFilter> requestFilters = Providers.getAllProviders(services,
                ContainerRequestFilter.class, new PriorityComparator<ContainerRequestFilter>(PriorityComparator.Order.ASCENDING));

        if (preMatch) {
            requestFilters = com.google.common.collect.Iterables.filter(requestFilters, PRE_MATCH_FILTER_PREDICATE);
        } else {
            requestFilters = com.google.common.collect.Iterables.filter(requestFilters, POST_MATCH_FILTER_PREDICATE);
        }

        for (ContainerRequestFilter filter : requestFilters) {
            try {
                filter.filter(requestContext);
                final Response abortResponse = requestContext.getAbortResponse();
                if (abortResponse != null) {
                    // abort accepting & return response
                    return Continuation.of(requestContext, Stages.asStage(
                            new Inflector<ContainerRequest, ContainerResponse>() {
                                @Override
                                public ContainerResponse apply(
                                        final ContainerRequest requestContext) {

                                    return new ContainerResponse(requestContext, abortResponse);
                                }
                            }));
                }
            } catch (IOException ex) {
                final Response abortResponse = requestContext.getAbortResponse();
                if (abortResponse == null) {
                    throw new WebApplicationException(ex);
                } else {
                    throw new WebApplicationException(ex, abortResponse);
                }
            }
        }
        return Continuation.of(requestContext, getDefaultNext());
    }

    private static class ResponseFilterStage extends AbstractChainableStage<ContainerResponse> {
        private final List<ContainerResponseFilter> filters;

        private ResponseFilterStage(List<ContainerResponseFilter> filters) {
            this.filters = filters;
        }

        @Override
        public Continuation<ContainerResponse> apply(ContainerResponse responseContext) {
            // TODO from the name-bound filters select only those that are applicable for the invoked resource.

            try {
                for (ContainerResponseFilter filter : filters) {
                    filter.filter(responseContext.getRequestContext(), responseContext);
                }
            } catch (IOException ex) {
                throw new WebApplicationException(ex);
            }

            return Continuation.of(responseContext, getDefaultNext());
        }
    }

    /**
     * Container filter processing injection binding module.
     */
    static class Module extends AbstractModule {

        @Override
        protected void configure() {
            bind().to(ContainerFilteringStage.Builder.class);
        }
    }
}
