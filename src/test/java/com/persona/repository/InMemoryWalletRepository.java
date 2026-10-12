package com.persona.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.persona.model.Wallet;

/**
 * A {@link WalletRepository} backed by a {@code HashMap}, for tests only.
 * <b>Day-07.</b>
 *
 * <h2>Why this lives in src/test and not src/main</h2>
 *
 * <p>{@code InMemoryUserRepository} is in {@code src/main} because it was
 * Day-00's way of having a working application before a database existed — it
 * was, briefly, the real implementation. This class never was. It exists so
 * {@code UserServiceTest} can keep building its subject with plain {@code new}
 * and no Spring context, which is the property that keeps that suite at
 * milliseconds.
 *
 * <p>Shipping a fake in {@code src/main} is how a fake eventually gets wired
 * into production by someone who finds it on the classpath and assumes it was
 * meant to be there. The test tree makes that impossible rather than unlikely —
 * the same reasoning the model uses everywhere else today.
 *
 * <h2>What this fake does NOT reproduce, and why it matters</h2>
 *
 * <p>Stated loudly because a fake that is trusted too far is worse than no
 * fake:
 *
 * <ul>
 *   <li><b>No UNIQUE constraint on user_id.</b> The real guarantee that one
 *       user has one wallet is a database constraint. This map would happily
 *       hold two wallets for the same user if {@code save} were called twice —
 *       the {@code existsByUserId} check in {@code WalletService.createFor} is
 *       the only thing stopping it here, and that check races. So this class
 *       can never prove the one-wallet-per-user rule; only
 *       {@code WalletRepositoryIT} against real Postgres can.</li>
 *   <li><b>No transactions.</b> Nothing here rolls back. A test asserting that
 *       a failed wallet creation also removes the user would PASS against this
 *       class for the wrong reason, or fail for the wrong reason, and in
 *       neither case would it be evidence. <b>That is why the transaction
 *       boundary is verified in an integration test against the real database,
 *       not here.</b></li>
 *   <li><b>No NUMERIC(19,4) rounding.</b> A {@code BigDecimal} put in comes
 *       back with the scale it went in with, where Postgres would return
 *       scale 4. This hides exactly the {@code equals}-versus-{@code compareTo}
 *       trap that the real column exposes.</li>
 * </ul>
 *
 * <p>In short: this class supports tests about <em>logic</em> and proves
 * nothing about <em>storage</em>. Every guarantee the schema makes is absent
 * here by construction.
 */
public class InMemoryWalletRepository implements WalletRepository {

    private final Map<Long, Wallet> byId = new HashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    /**
     * Null-tolerant on purpose, and the reason is a real finding rather than
     * defensive padding.
     *
     * <p>{@code InMemoryUserRepository} never assigns ids — it is a
     * {@code HashMap} keyed by email, and {@code User.id} is populated by the
     * database's IDENTITY column, which a map does not have. So in
     * {@code UserServiceTest}, {@code register} saves a user whose id is still
     * {@code null} and then calls {@code walletService.createFor(user)} with
     * it. The first version of this method called {@code userId.equals(...)}
     * and produced a {@code NullPointerException} in three tests.
     *
     * <p>The honest fix is here rather than in {@code WalletService}, because
     * the service is right: against a real database the id is never null at
     * that point, since {@code save} has flushed and the IDENTITY value has
     * been assigned. A null-check in production code to accommodate a test
     * fake's limitation would be the fake dictating the design.
     *
     * <p>SQL semantics are also what this mirrors. {@code WHERE user_id = NULL}
     * matches nothing in Postgres — not an error, simply no rows — so
     * returning {@code Optional.empty()} is what the real repository would do.
     * <b>A fake that diverges from the real thing's semantics is worse than no
     * fake, because its results get trusted.</b>
     */
    @Override
    public Optional<Wallet> findByUserId(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return byId.values().stream()
                .filter(w -> w.getUser() != null
                        && userId.equals(w.getUser().getId()))
                .findFirst();
    }

    @Override
    public boolean existsByUserId(Long userId) {
        return findByUserId(userId).isPresent();
    }

    /**
     * Assigns an id by reflection, because {@link Wallet#getId()} has no setter
     * — the real id comes from the database's IDENTITY column.
     *
     * <p>Reflection in a test fake is acceptable where it would not be in
     * production code: the alternative is adding a {@code setId} to the entity
     * purely so a test can call it, which would widen the production API to
     * suit a fake. The test bends, not the model.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <S extends Wallet> S save(S wallet) {
        if (wallet.getId() == null) {
            try {
                var field = Wallet.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(wallet, sequence.incrementAndGet());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("could not assign a test id", e);
            }
        }
        byId.put(wallet.getId(), wallet);
        return wallet;
    }

    @Override
    public Optional<Wallet> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Wallet> findAll() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public long count() {
        return byId.size();
    }

    @Override
    public void deleteById(Long id) {
        byId.remove(id);
    }

    @Override
    public void delete(Wallet wallet) {
        byId.remove(wallet.getId());
    }

    @Override
    public boolean existsById(Long id) {
        return byId.containsKey(id);
    }

    @Override
    public void deleteAll() {
        byId.clear();
    }

    // -----------------------------------------------------------------------
    //  Everything below is JpaRepository surface this fake does not implement.
    //
    //  THIS IS THE COST OF NOT HAVING A NARROW INTERFACE, made visible.
    //
    //  WalletRepository extends JpaRepository directly rather than being a
    //  six-method vocabulary with an adapter, as UserRepository is. The file
    //  comment on WalletRepository argues that trade is worth it. This block
    //  is the bill: a fake must stub roughly twenty methods nobody calls,
    //  because the interface is JPA's rather than the application's.
    //
    //  Worth remembering the next time the choice comes up. The narrow
    //  interface costs one extra file in src/main; the wide one costs this.
    // -----------------------------------------------------------------------

    @Override public List<Wallet> findAll(Sort sort) { return findAll(); }
    @Override public Page<Wallet> findAll(Pageable pageable) { throw unsupported(); }
    @Override public <S extends Wallet> List<S> saveAll(Iterable<S> entities) { throw unsupported(); }
    @Override public List<Wallet> findAllById(Iterable<Long> ids) { throw unsupported(); }
    @Override public void deleteAllById(Iterable<? extends Long> ids) { throw unsupported(); }
    @Override public void deleteAll(Iterable<? extends Wallet> entities) { throw unsupported(); }
    @Override public void flush() { /* no-op: nothing is buffered */ }
    @Override public <S extends Wallet> S saveAndFlush(S entity) { return save(entity); }
    @Override public <S extends Wallet> List<S> saveAllAndFlush(Iterable<S> entities) { throw unsupported(); }
    @Override public void deleteAllInBatch(Iterable<Wallet> entities) { throw unsupported(); }
    @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { throw unsupported(); }
    @Override public void deleteAllInBatch() { byId.clear(); }
    @Override public Wallet getReferenceById(Long id) { throw unsupported(); }

    /*
     * Deprecated in Spring Data, still abstract, therefore still mandatory.
     *
     * This one was not anticipated when the file was written: the compiler
     * found it, which is the compiler doing its job. Noted rather than quietly
     * fixed, because it is the WalletRepository trade billing itself a second
     * time in the same file — a wide interface means the fake owes methods
     * nobody has heard of, including ones the framework itself has given up on.
     */
    @Override @Deprecated public Wallet getById(Long id) { throw unsupported(); }
    @Override @Deprecated public Wallet getOne(Long id) { throw unsupported(); }
    @Override public <S extends Wallet> Optional<S> findOne(Example<S> example) { throw unsupported(); }
    @Override public <S extends Wallet> List<S> findAll(Example<S> example) { throw unsupported(); }
    @Override public <S extends Wallet> List<S> findAll(Example<S> example, Sort sort) { throw unsupported(); }
    @Override public <S extends Wallet> Page<S> findAll(Example<S> example, Pageable pageable) { throw unsupported(); }
    @Override public <S extends Wallet> long count(Example<S> example) { throw unsupported(); }
    @Override public <S extends Wallet> boolean exists(Example<S> example) { throw unsupported(); }
    @Override public <S extends Wallet, R> R findBy(Example<S> example,
            java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) {
        throw unsupported();
    }

    /**
     * Throws rather than returning a plausible empty value.
     *
     * <p>A stub returning {@code null} or an empty list would let a test quietly
     * exercise a path this fake does not actually model, and pass. Failing
     * loudly means any test that wanders in gets told, immediately, that it
     * needs the real repository.
     */
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(
                "InMemoryWalletRepository is a test fake and does not implement this; "
                        + "use WalletRepositoryIT against real Postgres instead");
    }
}
