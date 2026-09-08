/**
 * <h2>Layer 3 - persistence.</h2>
 *
 * The only package allowed to know HOW users are stored. Today that will be an
 * in-memory {@code Map} (slice 4); on Day-04 it becomes PostgreSQL via JPA.
 *
 * <p>The point of isolating it: when the storage swaps from Map to Postgres,
 * {@code service} and {@code controller} do not change a single line, because they
 * talk to an <em>interface</em> here, not to the implementation.
 *
 * <p>Real-life analogy: the service is a chef who asks for "eggs". The repository is
 * the fridge. Replace the fridge with a grocery delivery service and the chef's
 * recipe is unchanged.
 */
package com.persona.repository;
