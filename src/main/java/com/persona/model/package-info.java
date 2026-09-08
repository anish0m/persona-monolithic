/**
 * <h2>Layer 4 - the domain model.</h2>
 *
 * Plain Java objects describing WHAT the app is about: {@code User}, later {@code Profile}.
 * No Spring annotations, no HTTP, no SQL. If you deleted Spring entirely, this package
 * would still compile.
 *
 * <p>Rule for this package: it depends on nothing else in the app. Everything else
 * depends on it. That is what keeps the domain stable while the outer layers churn.
 *
 * <p>Slice 2 fills this in with the {@code User} class.
 */
package com.persona.model;
