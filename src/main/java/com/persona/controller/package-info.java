/**
 * <h2>Layer 1 - the web edge.</h2>
 *
 * Translates HTTP into method calls and back:
 * <pre>
 *   POST /api/users  --(JSON)-->  Controller  --(Java object)-->  Service
 *                    &lt;--(JSON)--             &lt;--(Java object)--
 * </pre>
 *
 * <p>Controllers should be thin. Their job is: read the request, call one service
 * method, map the result to a status code. Any {@code if} statement here that is
 * about business rules belongs in {@code service} instead.
 *
 * <p>Filled in on Day-01/Day-02.
 */
package com.persona.controller;
