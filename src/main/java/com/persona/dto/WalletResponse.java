package com.persona.dto;

import java.time.Instant;

import com.persona.model.Wallet;
import com.persona.model.WalletStatus;

/**
 * What persona sends back when asked about a wallet. Day-07.
 *
 * <h2>The balance is a String, and this is the point of the record</h2>
 *
 * <p>Every other decision today protects the exactness of the balance —
 * {@code NUMERIC(19,4)} in the schema, {@code BigDecimal} in the entity,
 * {@code precision}/{@code scale} matching so a mismatch fails the boot. All of
 * it is undone at the last inch if the number leaves as a JSON number:
 *
 * <pre>
 * Postgres NUMERIC(19,4) ──► BigDecimal ──► Jackson ──► JSON ──► JavaScript
 *       1.1000        exact      1.1000       ???                  double
 * </pre>
 *
 * <p>Two separate losses hide in that last arrow. Jackson serialises a
 * {@code BigDecimal} as a JSON number by default, so {@code 1.1000} is written
 * as {@code 1.1} and the scale is gone — a client cannot tell whether the
 * system tracks four decimal places or one. Worse, <b>JSON numbers are parsed
 * into IEEE-754 doubles by every JavaScript client in existence</b>, which
 * hands the value to the exact type the entire day was spent avoiding, after
 * the database, Hibernate and the service layer all got it right.
 *
 * <p>So money leaves as a string: {@code "balance": "1.1000"}, never
 * {@code "balance": 1.1}. This is not a persona quirk — Stripe sends integer
 * minor units, PayPal sends strings. Nobody serious sends a JSON float for
 * money.
 *
 * <p>{@code toPlainString()} rather than {@code toString()} because
 * {@code toString()} may emit scientific notation ({@code 1E+2}) at some
 * scales. Out of range for persona, and it costs nothing to be exact.
 *
 * <p>Note the companion setting in {@code application.properties},
 * {@code spring.jackson.generator.write-bigdecimal-as-plain=true}. It is
 * belt-and-braces: this record already converts explicitly, but the property
 * catches any {@code BigDecimal} that reaches Jackson by some other route — a
 * future DTO where someone forgets. The explicit conversion is the mechanism;
 * the property is the backstop. Same shape as the Java check and the database
 * CHECK constraint on the balance itself.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <ul>
 *   <li><b>No {@code user} field.</b> Serialising the owner would mean
 *       traversing the LAZY association, which outside a transaction throws
 *       {@code LazyInitializationException} (open-in-view is off, by design)
 *       and inside one issues an extra query to populate fields the client did
 *       not ask for. The caller is the owner; they know who they are.</li>
 *   <li><b>No internal wallet id in the hands of other users.</b> The id is
 *       included because the owner may legitimately need to reference their own
 *       wallet, but no endpoint returns somebody else's wallet, so it never
 *       becomes an enumeration surface.</li>
 * </ul>
 */
public record WalletResponse(
        Long id,

        /*
         * A String. See the class comment — this single type choice is what
         * keeps four decimal places alive across the wire, and the reason it
         * looks odd is exactly why it needs the comment.
         */
        String balance,

        /*
         * The enum serialises to its NAME ("ACTIVE"), because Jackson's default
         * for an enum is Enum.name(). That is the right default here and worth
         * noticing as the mirror of the @Enumerated decision: the database
         * stores the name rather than the ordinal, and so does the wire. A
         * client that switches on "ACTIVE" keeps working when a fourth state is
         * added; one that switches on 0 does not.
         */
        WalletStatus status,

        Instant createdAt) {

    /**
     * The one place a {@code Wallet} becomes a {@code WalletResponse}.
     *
     * <p>A static factory on the DTO rather than a method on {@code Wallet},
     * because the model must not know a web layer exists. The arrows point one
     * way: the DTO may depend on the model; the model may not depend on the
     * DTO. Same rule as {@link UserResponse#from}.
     */
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getBalance().toPlainString(),
                wallet.getStatus(),
                wallet.getCreatedAt());
    }
}
