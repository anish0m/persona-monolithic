/**
 * <h2>Cross-cutting - failure vocabulary.</h2>
 *
 * Custom exceptions such as {@code UserNotFoundException} and
 * {@code DuplicateEmailException} (slice 4).
 *
 * <p>Why a whole package for this: it lets the service layer report failure in
 * <em>domain</em> words rather than HTTP words. The service throws
 * {@code UserNotFoundException}; the controller is the one that decides that means
 * {@code 404}. Same exception, reused by a CLI, would mean "print an error".
 */
package com.persona.exception;
