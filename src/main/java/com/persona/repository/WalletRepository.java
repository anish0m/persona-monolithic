package com.persona.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persona.model.Wallet;

/**
 * Storage for wallets. Day-07.
 *
 * <h2>Why this is ONE interface where users have FIVE files</h2>
 *
 * <p>The user side of this project has {@code UserRepository} (the vocabulary),
 * {@code SpringDataUserRepository} (the Spring Data proxy),
 * {@code InMemoryUserRepository}, {@code JdbcUserRepository} and
 * {@code JpaUserRepository} — five files, three live implementations, selected
 * by profile. The wallet gets one interface and no adapter. That is a
 * deliberate break from the established pattern, and breaking a pattern
 * silently is how a codebase becomes incoherent, so here is the reasoning.
 *
 * <p><b>Those five files exist because of what they taught, not because the
 * application needs them.</b> The in-memory version was Day-00's way of having
 * a working app before a database existed. The JDBC and JPA pair exist side by
 * side so the hand-written SQL can be read next to the version that generates
 * it — the comparison IS the lesson. The narrow {@code UserRepository}
 * interface exists to demonstrate that a vocabulary limits what callers can
 * say.
 *
 * <p>None of those reasons apply to a second aggregate. Writing four wallet
 * implementations would not teach the comparison a second time; it would just
 * be four places for one bug to hide, and four things to keep in sync. Day-06
 * already charged for exactly this: adding one {@code role} column meant
 * touching four repositories and two integration suites.
 *
 * <p><b>The rule worth extracting: a pattern that exists to teach something has
 * done its job once. Repeating it after that is cargo cult.</b> If a second
 * storage mechanism for wallets is ever genuinely needed, extracting an
 * interface at that moment is a ten-minute refactor — which is precisely the
 * argument Day-03 used for extracting {@code UserRepository} when the second
 * implementation appeared, rather than before.
 *
 * <h2>Why there is no adapter hiding JpaRepository</h2>
 *
 * <p>{@code UserService} is kept from seeing {@code flush()},
 * {@code getReferenceById()} and {@code deleteAllInBatch()} by the narrow
 * {@code UserRepository} interface. That protection is real, and it is given up
 * here.
 *
 * <p>Stated plainly so the trade is visible rather than accidental: this
 * interface hands {@code WalletService} the full {@code JpaRepository}
 * vocabulary, including methods that have no business being called from a
 * service layer. The defence is that {@code WalletService} is small and its
 * every method is reviewed — a weaker guarantee than a type that makes the call
 * unsayable. If the service grows, extracting the narrow interface is the first
 * thing to do.
 *
 * <h2>findByUserId, and the field that is not there</h2>
 *
 * <p>{@code findByUserId} traverses the {@code user} association and reads its
 * {@code id} — Spring Data parses the method name against the ENTITY'S field
 * names, producing {@code SELECT w FROM Wallet w WHERE w.user.id = ?1}. The
 * generated SQL touches only {@code wallets}: {@code user_id} is a column on
 * that table, so no join is required to filter on it.
 *
 * <p>This is the method that makes a {@code User.wallet} field unnecessary, and
 * that is the point. See {@link com.persona.model.Wallet#getUser()} for why the
 * inverse side would cost a second query on every login — and why, on Day-17,
 * this one call is the only thing that has to become an HTTP request.
 *
 * <p>Note the same limit that applies to {@code findByEmail} applies here:
 * Spring Data validates that the property EXISTS, not that it was the one you
 * meant. {@code findByUserId} and a hypothetical {@code findById} both parse
 * perfectly and mean entirely different things — one takes a user's id, the
 * other a wallet's. Only a test checks meaning.
 */
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    /**
     * The wallet belonging to a user, by the user's id.
     *
     * <p>{@code Optional} rather than {@code Wallet} because absence is
     * genuinely possible and is not always an error — a caller checking whether
     * a user already has a wallet expects to find nothing sometimes. Callers
     * for whom absence IS an error use {@code WalletService.getByUserId},
     * which throws. Slice 4's rule, unchanged: the repository tolerates
     * absence, the service decides whether it is a problem.
     *
     * <p><b>At most one is a guarantee here, not a hope.</b>
     * {@code wallets.user_id} carries a {@code UNIQUE} constraint, so this can
     * never find two rows. Without that constraint this signature would be a
     * lie that holds until the day it does not, and would surface as an
     * {@code IncorrectResultSizeDataAccessException} far from the insert that
     * caused it.
     */
    Optional<Wallet> findByUserId(Long userId);

    /**
     * Whether a user already has a wallet.
     *
     * <p>Exists so the signup path can check without loading the row — the
     * generated query is a {@code SELECT COUNT}, not a {@code SELECT *}, which
     * matters nowhere at persona's size and is the correct habit regardless.
     */
    boolean existsByUserId(Long userId);
}
