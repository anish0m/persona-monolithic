package com.persona.exception;

/**
 * Thrown when a signup uses an email that is already registered.
 *
 * <p>This is the counterpart to {@link UserNotFoundException} and the reason both
 * exist as separate types: they are opposite failures and deserve opposite
 * responses. Day-09 maps this one to HTTP 409 Conflict and the other to 404 Not
 * Found. One shared "UserException" type would put a string comparison in the
 * handler to tell them apart, which is a type system being emulated badly.
 *
 * <p><b>A caution about where this rule lives.</b> Checking for a duplicate means
 * asking "does any other user already have this email?" — a question about the
 * whole collection, not about one User. That is why it cannot live in the model,
 * by exactly the test used for password rules in slice 2: the object cannot
 * answer it using only itself.
 *
 * <p><b>And a caution about the check itself.</b> "Look, then insert" is safe
 * here because the store is a single-threaded map in one process. Under real
 * concurrency two signups can both look, both see nothing, and both insert — a
 * race condition. The real fix is a {@code UNIQUE} constraint in PostgreSQL, so
 * the database enforces it atomically and this exception becomes the translation
 * of a constraint violation rather than the result of a guess. Day-03 adds that
 * constraint. Note the shape of the lesson: the application-level check is a
 * convenience for a good error message, not the actual guarantee.
 */
public class DuplicateEmailException extends RuntimeException {

    private final String email;

    public DuplicateEmailException(String email) {
        super("A user is already registered with email: " + email);
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}
