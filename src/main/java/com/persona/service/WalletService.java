package com.persona.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.persona.exception.WalletNotFoundException;
import com.persona.model.User;
import com.persona.model.Wallet;
import com.persona.repository.WalletRepository;

/**
 * Business operations on wallets. Day-07.
 *
 * <h2>How thin this class is, and why that is the goal</h2>
 *
 * <p>Compare {@link #deposit} with what it would look like if the rules lived
 * here instead of on the entity:
 *
 * <pre>
 *     // if the rules lived in the service
 *     Wallet w = getByUserId(userId);
 *     if (w.getStatus() != ACTIVE)        throw ...;
 *     if (amount.compareTo(ZERO) &lt;= 0)    throw ...;
 *     w.setBalance(w.getBalance().add(amount));
 * </pre>
 *
 * <p>That version works and is wrong, for a reason that only shows up later: it
 * requires {@code setBalance} to exist, and the moment a setter for the balance
 * exists, every rule in this class is optional for anyone who finds it. The
 * second caller — a scheduled job, an admin tool, a data fix — reaches for the
 * setter and the checks are simply absent.
 *
 * <p>Pushing both checks into {@link Wallet#credit} makes that impossible to
 * express rather than merely discouraged, and leaves this class doing the two
 * things a service genuinely owns: <b>finding the aggregate, and defining the
 * transaction boundary around the operation.</b>
 *
 * <p>Which is the same test applied to {@code UserService} since Day-01: if
 * HTTP disappeared tomorrow and persona were driven from a command line, every
 * method here would still be correct and still be called.
 */
@Service
public class WalletService {

    private final WalletRepository repository;

    public WalletService(WalletRepository repository) {
        this.repository = repository;
    }

    /**
     * Opens a wallet for a newly registered user.
     *
     * <p><b>This method does not annotate itself {@code @Transactional}, and
     * that is deliberate.</b> It is called from
     * {@code UserService.register}, which owns the boundary — the user row and
     * the wallet row must commit together or not at all. Spring's default
     * propagation is {@code REQUIRED}, which means "join the caller's
     * transaction if one exists, start one otherwise", so this method
     * participates in the caller's transaction automatically.
     *
     * <p>Annotating it {@code @Transactional} as well would be harmless but
     * misleading: it would read as though this method defines a boundary, when
     * in the one case that matters it does not. <b>A transaction boundary
     * should be obvious from where it is written.</b> One business operation —
     * "sign up" — is one transaction, even though it is two inserts across two
     * services.
     *
     * <p>See {@code UserService.register} for the full account of what happens
     * when that boundary is missing, which is the central lesson of Day-07.
     */
    public Wallet createFor(User user) {
        // Not a correctness guarantee — the UNIQUE constraint on
        // wallets.user_id is, and it cannot be raced the way this check can.
        // This exists so the failure reads as "that user already has a wallet"
        // rather than as a constraint-violation stack trace. Same relationship
        // as UserService's duplicate-email check to the users_email_key index:
        // the check buys the error message, the constraint buys the safety.
        if (repository.existsByUserId(user.getId())) {
            throw new IllegalStateException(
                    "user " + user.getId() + " already has a wallet");
        }

        return repository.save(new Wallet(user));
    }

    /**
     * The wallet belonging to a user, or an exception.
     *
     * <p>Throws rather than returning {@code Optional} because every caller of
     * this method has already authenticated as the user in question — at that
     * point a missing wallet is not a normal answer but a broken invariant,
     * since registration creates one. Forcing those callers to unwrap an
     * {@code Optional} only makes them write an {@code if} they have no answer
     * for.
     *
     * <p>{@code readOnly = true} tells Hibernate not to take dirty-checking
     * snapshots of what it loads, and lets the driver flag the transaction as
     * read-only. The practical effect at persona's size is nil. It is written
     * because it documents intent in a way a comment cannot: a reader knows
     * this path cannot write, and so does the database.
     */
    @Transactional(readOnly = true)
    public Wallet getByUserId(Long userId) {
        return repository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));
    }

    /**
     * Adds money to a user's wallet.
     *
     * <p><b>{@code @Transactional} here is not optional, even though this looks
     * like a single write.</b> It reads the wallet, mutates it, and relies on
     * Hibernate's dirty checking to issue the UPDATE at commit. Without a
     * transaction spanning the method there is no persistence context, the
     * entity returned by the repository is detached, {@code credit} mutates a
     * plain Java object, and <b>nothing is written</b> — silently, with a green
     * test if the test asserts on the returned object rather than on the
     * database.
     *
     * <p>That is the same failure mode {@code UserService.updateProfile}
     * documents, which is why its integration test reads the row back with
     * {@code JdbcClient} rather than asking Hibernate. The wallet tests do the
     * same thing for the same reason.
     *
     * <p>Note there is no {@code save()} call. Under JPA the setter — here,
     * {@code credit} — IS the write: the entity is managed, Hibernate holds a
     * snapshot, and at commit it compares and issues an UPDATE for whatever
     * differs. Adding {@code save()} would not be wrong, merely redundant, and
     * it would teach the wrong model of what is happening.
     */
    @Transactional
    public Wallet deposit(Long userId, BigDecimal amount) {
        Wallet wallet = repository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));

        // Every rule lives in here. This line is the whole operation.
        wallet.credit(amount);

        return wallet;
    }

    /**
     * Suspends a wallet. Admin-facing.
     *
     * <p>Same dirty-checking arrangement as {@link #deposit}: the state
     * transition is a mutation on a managed entity inside a transaction.
     */
    @Transactional
    public Wallet freeze(Long userId) {
        Wallet wallet = repository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));
        wallet.freeze();
        return wallet;
    }

    /** Reverses {@link #freeze}. Admin-facing. */
    @Transactional
    public Wallet unfreeze(Long userId) {
        Wallet wallet = repository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));
        wallet.unfreeze();
        return wallet;
    }
}
