package com.persona.model;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import com.persona.exception.InsufficientFundsException;
import com.persona.exception.WalletNotActiveException;

/**
 * A user's money. Day-07.
 *
 * <p>This class is where the day's three Java traps are prevented, and each
 * prevention is one keyword that would otherwise default to something that
 * compiles, runs, and is wrong: {@code BigDecimal} instead of {@code double},
 * {@code EnumType.STRING} instead of the default ORDINAL, and
 * {@code FetchType.LAZY} on the owning side of the {@code @OneToOne}.
 *
 * <h2>The rules live here, not in the controller</h2>
 *
 * <p>The tempting placement for "a frozen wallet cannot receive money" is the
 * controller, where the 409 is returned. That is the wrong home, and the reason
 * is not style:
 *
 * <pre>
 *     // WalletController — WRONG
 *     if (wallet.getStatus() == WalletStatus.FROZEN) {
 *         return ResponseEntity.status(409).build();
 *     }
 * </pre>
 *
 * <p>Add a second entry point — a scheduled job, an admin tool, an import —
 * and the check is simply <em>absent</em> there. The rule would protect HTTP
 * rather than money.
 *
 * <p>Putting it in {@link #credit} instead means there is no code path that
 * reaches the balance while skipping the check, because CHANGING the balance IS
 * calling the guarded method. The controller shrinks to translating an
 * exception into a status code, which is all a controller should ever do.
 *
 * <pre>
 *    HTTP concern          business rule           durability
 * ┌───────────────┐    ┌──────────────────┐    ┌──────────────┐
 * │  Controller   │───►│ Wallet.credit()  │───►│   Postgres   │
 * │ 409 from exc  │    │ status + amount  │    │ CHECK >= 0   │
 * └───────────────┘    └──────────────────┘    └──────────────┘
 *                       the ONLY way in         the backstop
 * </pre>
 *
 * <p>Three layers, three different kinds of defence. The CHECK constraint is
 * not redundant with the Java check — it is what still holds when a future code
 * path forgets, or when someone runs an UPDATE by hand at 2am.
 *
 * @see WalletStatus for why CLOSED is terminal and why FROZEN blocks deposits
 */
@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The owner. This is the <b>owning side</b> of the relationship, in the JPA
     * sense: {@code wallets} holds the foreign key column, so this is the side
     * that gets {@code @JoinColumn}.
     *
     * <p><b>Why {@code @OneToOne} and not {@code @ManyToOne}:</b> because
     * {@code wallets.user_id} carries a {@code UNIQUE} constraint. That is the
     * tell, and it is more reliable than reasoning about the objects — the
     * schema already knows the cardinality.
     *
     * <p><b>{@code optional = false}</b> mirrors the column's {@code NOT NULL}.
     * It is not merely documentation: it tells Hibernate the association can
     * never be absent, which is what makes {@code LAZY} achievable at all on
     * this side. If the association were optional, Hibernate would have to
     * query to discover whether to place {@code null} or a proxy in the field,
     * and the laziness would be silently lost.
     *
     * <p><b>{@code FetchType.LAZY}</b> because loading a wallet almost never
     * needs the whole user. {@code WalletResponse} exposes the owner's id and
     * nothing else, so eager fetching would mean a join on every balance check
     * to populate fields that are immediately discarded.
     *
     * <h2>Why {@code User} has no {@code wallet} field pointing back</h2>
     *
     * <p>The inverse side is optional, and persona deliberately does not have
     * one. Three reasons, the second being the one that is not obvious:
     *
     * <ul>
     *   <li>It is never needed. Every wallet lookup starts from an
     *       authenticated email and goes to
     *       {@code walletRepository.findByUserId(...)}. Nothing starts from a
     *       {@code User} object and asks for its wallet.</li>
     *   <li><b>{@code @OneToOne} on the INVERSE side cannot be lazy</b> in
     *       Hibernate. The owning side can (see above) and {@code @ManyToOne}
     *       can, but the inverse side cannot: Hibernate must run a query to
     *       know whether to place {@code null} or a proxy in the field. So a
     *       {@code User.wallet} field would add a second query to every
     *       {@code findByEmail} during login, forever. The field looks free and
     *       costs a query on the hottest path in the application.</li>
     *   <li>It is a Day-17 landmine. Once wallets move to their own service, a
     *       {@code User.wallet} field is a compile error at every use site. A
     *       {@code findByUserId} call is one HTTP client swap.</li>
     * </ul>
     *
     * <p>Had the inverse side been written, it would use
     * {@code @OneToOne(mappedBy = "user")} and never {@code @JoinColumn}.
     * {@code mappedBy} says: the {@code user} field over here already owns this
     * relationship, do not create a second column for it. Omit it and Hibernate
     * treats both sides as owners and tries to add a {@code wallet_id} column
     * to {@code users} — a column the schema does not have, so
     * {@code ddl-auto=validate} fails the boot. That is the good outcome.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true, updatable = false)
    private User user;

    /**
     * The money. {@code BigDecimal}, and the type is the whole point.
     *
     * <p>{@code double} is binary fractions: {@code 0.1 + 0.2} is
     * {@code 0.30000000000000004}, which is not a bug but the specified
     * behaviour of IEEE 754. The real cost is not the lost fraction — it is
     * losing the ability to tell an arithmetic error from an attack, because
     * once totals drift on their own a discrepancy proves nothing.
     *
     * <p><b>{@code precision} and {@code scale} must match the DDL</b>
     * ({@code NUMERIC(19,4)}) or {@code ddl-auto=validate} refuses to start.
     * That is the check working, not an inconvenience.
     *
     * <p>Scale 4 rather than 2 so that intermediate results keep their
     * precision and rounding happens ONCE, at the end — a 2.5% fee on 10.01 is
     * 0.25025. Rounding at every step compounds the error.
     *
     * <h2>Two BigDecimal traps that do not announce themselves</h2>
     *
     * <ul>
     *   <li><b>Never {@code new BigDecimal(double)}.</b> It faithfully
     *       preserves the error already present in the double:
     *       {@code new BigDecimal(0.1)} is
     *       {@code 0.1000000000000000055511151231257827021181583404541015625}.
     *       Construct from a {@code String}, or from {@code valueOf}.</li>
     *   <li><b>{@code equals} compares SCALE as well as value.</b>
     *       {@code new BigDecimal("0.0").equals(new BigDecimal("0.00"))} is
     *       {@code false}. Use {@code compareTo(...) == 0} in every assertion
     *       about money — {@code assertEquals(BigDecimal.ZERO, getBalance())}
     *       fails on a perfectly correct wallet, because {@code ZERO} has scale
     *       0 while Postgres returns this column at scale 4, as
     *       {@code 0.0000}.</li>
     * </ul>
     */
    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    /**
     * {@code EnumType.STRING}, never the default.
     *
     * <p>The default is ORDINAL, which stores the constant's index. See
     * {@link WalletStatus} for the full account of why that silently rewrites
     * history the day somebody reorders the enum. In one line: STRING stores
     * {@code 'ACTIVE'}, which survives reordering, is readable in psql, and is
     * what makes the {@code CHECK (status IN (...))} constraint expressible at
     * all.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WalletStatus status;

    /**
     * Set by the database default, never by Java.
     *
     * <p>{@code insertable = false, updatable = false} for the same reason
     * {@link User#getCreatedAt()} does it: the value comes from Postgres's
     * {@code now()}, so the application must not send one. Two clocks that can
     * disagree is one clock too many.
     */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * Required by JPA, used by nobody else — hence {@code protected}.
     *
     * <p>Hibernate instantiates entities reflectively before populating their
     * fields, so it needs a no-argument constructor. Making it {@code protected}
     * rather than {@code public} means application code cannot accidentally
     * create a wallet with no owner and a null balance.
     */
    protected Wallet() {
    }

    /**
     * Opens a new wallet for a user, with a zero balance and ACTIVE status.
     *
     * <p>The only public constructor, and it takes the one thing a wallet
     * cannot exist without. There is deliberately no constructor that accepts
     * an opening balance: money appears through {@link #credit}, which is
     * guarded, logged by the caller and auditable. A constructor that quietly
     * accepts a starting amount would be an unguarded way to create money.
     *
     * <p>{@code BigDecimal.ZERO} rather than {@code new BigDecimal(0.0)} —
     * trap A above, and the constant exists precisely so nobody has to think
     * about it.
     */
    public Wallet(User user) {
        if (user == null) {
            throw new IllegalArgumentException("a wallet must have an owner");
        }
        this.user = user;
        this.balance = BigDecimal.ZERO;
        this.status = WalletStatus.ACTIVE;
    }

    /**
     * Adds money. The guarded path, and the ONLY way the balance increases.
     *
     * <p>Both checks live here rather than in a service or a controller,
     * because this method <em>is</em> the mutation: there is no way to reach
     * the balance that bypasses them. See the class comment for why that
     * placement is the point rather than a preference.
     *
     * <p>Note {@code this.balance = this.balance.add(amount)} and not
     * {@code this.balance.add(amount)}. {@code BigDecimal} is immutable, like
     * {@code String} — every operation returns a NEW object. The second form
     * compiles, runs, computes the right answer, discards it, and reports no
     * error of any kind.
     *
     * @throws WalletNotActiveException  if the wallet is FROZEN or CLOSED
     * @throws IllegalArgumentException  if the amount is null, zero or negative
     */
    public void credit(BigDecimal amount) {
        requirePositive(amount);

        if (!status.allowsMovement()) {
            throw new WalletNotActiveException(id, status);
        }

        this.balance = this.balance.add(amount);
    }

    /**
     * Removes money. Not reachable over HTTP in persona — there is no transfer
     * feature here — but written now because its existence is what makes
     * {@link #credit} honest about being one half of a pair, and because the
     * non-negative rule has to live somewhere that cannot be skipped.
     *
     * <p>The balance check is a read-then-write and therefore <b>races</b>: two
     * concurrent debits can both read a sufficient balance before either
     * writes. That is not fixed here, and deliberately so — the fix is a
     * conditional write ({@code UPDATE ... WHERE id = ? AND balance >= ?}) or
     * optimistic locking, which is a concurrency topic. What stops the race
     * from corrupting data TODAY is the database's
     * {@code wallets_balance_non_negative} CHECK, which cannot be raced because
     * Postgres serialises it.
     *
     * <p>Which is this project's recurring rule, in its money form: <b>never
     * let the only copy of a correctness guarantee live in application code.</b>
     * Application code races. Constraints do not.
     */
    public void debit(BigDecimal amount) {
        requirePositive(amount);

        if (!status.allowsMovement()) {
            throw new WalletNotActiveException(id, status);
        }

        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(id, balance, amount);
        }

        this.balance = this.balance.subtract(amount);
    }

    /**
     * Suspends the wallet. Idempotent: freezing a frozen wallet is not an
     * error, because the caller's intent ("this must not transact") is already
     * satisfied and failing would make retries dangerous.
     *
     * @throws WalletNotActiveException if the wallet is CLOSED — a terminal
     *                                  state does not accept transitions
     */
    public void freeze() {
        if (status == WalletStatus.CLOSED) {
            throw new WalletNotActiveException(id, status);
        }
        this.status = WalletStatus.FROZEN;
    }

    /** Reverses {@link #freeze}. Also refuses to resurrect a CLOSED wallet. */
    public void unfreeze() {
        if (status == WalletStatus.CLOSED) {
            throw new WalletNotActiveException(id, status);
        }
        this.status = WalletStatus.ACTIVE;
    }

    /**
     * Shuts the wallet permanently. There is no {@code reopen()} — see
     * {@link WalletStatus#CLOSED}.
     *
     * <p>Refuses while money remains. Closing a wallet with a balance would
     * strand funds in a state that can never transact again, and "where did my
     * money go" is not a question an audit should have to answer.
     */
    public void close() {
        if (balance.compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalStateException(
                    "cannot close a wallet holding " + balance.toPlainString());
        }
        this.status = WalletStatus.CLOSED;
    }

    /**
     * {@code compareTo}, not {@code equals} — and this is the trap, inlined at
     * the one place it would otherwise be written by hand each time.
     *
     * <p>{@code amount.equals(BigDecimal.ZERO)} would be {@code false} for a
     * {@code 0.0000} arriving from the database, because {@code equals}
     * compares scale as well as value.
     */
    private static void requirePositive(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "amount must be positive, was " + amount.toPlainString());
        }
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    /**
     * The balance.
     *
     * <p>No setter, by design. The balance is not a property to be assigned but
     * the result of guarded operations — {@link #credit} and {@link #debit}. A
     * {@code setBalance} would be an unguarded way to create or destroy money,
     * and would make every rule in this class optional.
     *
     * <p>{@code BigDecimal} is immutable, so handing the reference out is safe:
     * a caller cannot modify the wallet through it.
     */
    public BigDecimal getBalance() {
        return balance;
    }

    /**
     * The status.
     *
     * <p>Also no setter, for the same reason: transitions go through
     * {@link #freeze}, {@link #unfreeze} and {@link #close}, which enforce that
     * CLOSED is terminal. A {@code setStatus} would make that rule a suggestion.
     */
    public WalletStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Deliberately does not include the balance.
     *
     * <p>Same instinct that keeps {@code passwordHash} out of
     * {@link User#toString()}: this string ends up in logs and exception
     * messages, and a balance in a log file is a disclosure waiting for the
     * wrong person to read it.
     */
    @Override
    public String toString() {
        return "Wallet{id=" + id + ", status=" + status + "}";
    }
}
