package com.persona.exception;

/**
 * Thrown when a lookup names a user that does not exist.
 *
 * <p><b>Why a new class instead of {@code throw new RuntimeException("not found")}?</b>
 * Because a caller cannot catch a string. With a dedicated type, a caller can
 * write {@code catch (UserNotFoundException e)} and handle exactly this case
 * while letting genuine bugs — a {@code NullPointerException}, a broken database
 * connection — keep propagating. Catching {@code RuntimeException} to handle
 * "not found" also swallows those, which is how a system ends up reporting
 * "user not found" for a crashed database.
 *
 * <p>It also carries information in a form code can use. The message is for a
 * human reading a log; {@link #getEmail()} is for the layer that has to build an
 * HTTP response. Parsing the email back out of the message string would work
 * until someone rewords the message.
 *
 * <p><b>Unchecked</b> ({@code extends RuntimeException}, not {@code Exception}).
 * A checked exception forces every method in the call chain to either catch it or
 * declare it, all the way up. Nothing between the repository and the controller
 * can do anything useful about a missing user, so the only thing checking buys is
 * {@code throws} clauses on twelve method signatures. Spring's own data access
 * exceptions are unchecked for exactly this reason.
 *
 * <p>Day-09 turns this type into an HTTP 404 in one place, via
 * {@code @ExceptionHandler}. That is only possible because the type exists.
 */
public class UserNotFoundException extends RuntimeException {

    private final String email;

    public UserNotFoundException(String email) {
        super("No user found with email: " + email);
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}
