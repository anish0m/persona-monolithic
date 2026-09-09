package com.persona.model;

import java.util.Optional;

/**
 * A person who can sign up and log in to persona.
 *
 * <p>This is a plain Java object. Notice what is NOT here:
 * no Spring annotation, no {@code @Entity}, no JSON annotation, no SQL.
 * The model layer is the one layer that depends on nothing, which is exactly
 * why every other layer is allowed to depend on it. Add a framework annotation
 * here and that property is gone.
 *
 * <p>Encapsulation: every field is {@code private}. Nothing outside this class
 * can reach in and change state directly. The only way in is a constructor or a
 * setter — and a setter is a method, which means it is a place where a rule can
 * later be enforced. A public field has no such place. That is the whole reason
 * for the ceremony; it is not decoration.
 */
public class User {

    /**
     * Chosen at construction and never changed afterwards, so it is {@code final}
     * and has no setter. The compiler now enforces that promise — a future
     * {@code setEmail} cannot be added by accident, only deliberately.
     *
     * <p>Email is persona's identity: it is what a person logs in with, and it is
     * what makes two User objects "the same person". Slice 4 builds equals/hashCode
     * on this field for that reason.
     */
    private final String email;

    private String firstName;
    private String lastName;

    /**
     * Stored as given for now. This is a known, temporary lie: Day-06 replaces it
     * with a BCrypt hash and this field becomes {@code passwordHash}. It is named
     * plainly today so that the change on Day-06 is visible and deliberate rather
     * than silent.
     */
    private String password;

    /**
     * A profile picture URL, or {@code null} when the person has not set one.
     *
     * <p>This is the first field that is genuinely allowed to be absent. Email,
     * name and password all have a rule that says "must be there"; this one does
     * not. That difference is real and the type system should carry it — see
     * {@link #getImage()}.
     *
     * <p>Stored as a plain nullable field rather than as {@code Optional<String>}.
     * {@code Optional} is designed as a return type, to force a caller to handle
     * the empty case. As a field it costs an extra object per User, does not
     * serialise cleanly, and adds nothing: the field is private, so the only way
     * anyone reads it is through the getter, which is where the protection needs
     * to be anyway.
     */
    private String image;

    /**
     * The only constructor. There is no no-arg constructor on purpose: a User
     * without an email is not a meaningful User, and leaving the no-arg form out
     * makes that unrepresentable rather than merely discouraged.
     *
     * <p>Note it delegates to the setters rather than assigning directly. Both
     * routes into a field — construction and later mutation — then run the same
     * validation, written once. Duplicate the rule instead and Day-06's switch to
     * BCrypt gets applied to one path and forgotten in the other, which lets an
     * unhashed password in through the constructor.
     */
    public User(String email, String firstName, String lastName, String password) {
        requireText(email, "Email");
        this.email = email;
        setFirstName(firstName);
        setLastName(lastName);
        setPassword(password);
    }

    /**
     * The model validates only what it can check <em>using itself</em>: null,
     * blank, length. That is the whole test for whether a rule belongs here.
     *
     * <p>Watch the same rule outgrow this class:
     * <ul>
     *   <li>"at least 8 characters" — fine, self-contained</li>
     *   <li>"cannot equal your email" — needs a sibling field; awkward but possible</li>
     *   <li>"cannot be one of your last 5 passwords" — needs the <b>database</b></li>
     * </ul>
     * The third one cannot live here without the model importing a repository,
     * which destroys the one property that makes this layer safe to depend on.
     * That rule belongs in the service. The split is not about importance; it is
     * about what the object can answer on its own.
     */
    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
    }

    public String getEmail() {
        return email;
    }

    public String getFirstName() {
        return firstName;
    }

    /**
     * Every field with a constructor rule needs the same rule on its setter, or
     * the rule only applies at birth — the object is protected when created and
     * unprotected for the rest of its life.
     */
    public void setFirstName(String firstName) {
        requireText(firstName, "First name");
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        requireText(lastName, "Last name");
        this.lastName = lastName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        requireText(password, "Password");
        this.password = password;
    }

    /**
     * persona's public handle: {@code @firstname-lastname}, lowercased.
     *
     * <p>There is no {@code username} field. It is <b>derived</b> — computed from
     * first and last name every time it is asked for. That is a deliberate choice
     * with a specific failure it avoids.
     *
     * <p>Store it as a field instead and there are now two places holding the same
     * truth. {@code setFirstName("Turhan")} updates one and not the other, and the
     * object is quietly lying: {@code getFirstName()} says Turhan while
     * {@code getUsername()} still says the old name. Every stored-derivative bug
     * looks like this. Deriving on read makes the inconsistency <em>unrepresentable</em>
     * rather than merely unlikely.
     *
     * <p>The cost is recomputation on every call. Two string operations. You would
     * only trade back to a stored field with a measurement proving it matters —
     * and then you would need a rule that recomputes it in every setter.
     *
     * <p>No setter, for the same reason: a username is not a thing you set, it is a
     * thing your name implies. To change it, change your name.
     */
    public String getUsername() {
        return "@" + firstName.toLowerCase() + "-" + lastName.toLowerCase();
    }

    /**
     * Returns the image URL, or an empty {@code Optional} when none is set.
     *
     * <p>This is what {@code Optional} is actually for. A plain {@code String}
     * return type tells the caller nothing: they must read the source, or the
     * docs, or find out at runtime via {@code NullPointerException} on
     * {@code user.getImage().length()}. An {@code Optional<String>} return type
     * makes absence part of the signature — the compiler will not let the caller
     * treat it as a String without first deciding what an absent one means.
     *
     * <p>Rule of thumb: {@code Optional} as a return type, never as a field, never
     * as a parameter.
     */
    public Optional<String> getImage() {
        return Optional.ofNullable(image);
    }

    /**
     * Accepts {@code null} — that is how you clear a profile picture — but rejects
     * blank.
     *
     * <p>The distinction matters: {@code null} means "no image", while {@code ""}
     * means "an image whose URL is the empty string", which is not a thing. Two
     * different values representing the same state is exactly the ambiguity
     * {@code Optional} exists to remove, so the setter refuses to create it.
     */
    public void setImage(String image) {
        if (image != null && image.isBlank()) {
            throw new IllegalArgumentException("Image cannot be blank — use null to clear it");
        }
        this.image = image;
    }

    /**
     * Deliberately excludes the password. {@code toString} output ends up in log
     * files, stack traces and IDE debugger views — all places a credential must
     * never reach. Forgetting this one method is a genuinely common way real
     * systems leak passwords into logs.
     */
    @Override
    public String toString() {
        return "User{email='" + email + "', firstName='" + firstName
                + "', lastName='" + lastName + "'}";
    }
}
