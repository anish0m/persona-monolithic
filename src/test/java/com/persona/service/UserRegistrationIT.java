package com.persona.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.persona.dto.CreateUserRequest;
import com.persona.model.User;
import com.persona.model.Wallet;
import com.persona.model.WalletStatus;
import com.persona.repository.WalletRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The transaction boundary on {@code UserService.register}, against a real
 * PostgreSQL database. <b>Day-07, and the most important test of the day.</b>
 *
 * <h2>Why this cannot be a unit test</h2>
 *
 * <p>{@code UserServiceTest} builds its subject with plain {@code new}. That
 * keeps it fast and is the right default — but {@code @Transactional} is
 * implemented by a <b>Spring-generated proxy</b>, and an object built with
 * {@code new} has no proxy around it. In that suite the annotation is not
 * merely untested; <b>it is not running at all.</b> A test there asserting
 * "the user is rolled back" would pass or fail for reasons having nothing to
 * do with the annotation.
 *
 * <p>So this file exists, and it is deliberately NOT annotated
 * {@code @Transactional} the way the repository ITs are. Those wrap each test
 * in a rolled-back transaction for isolation. Doing that here would be fatal:
 * the method under test would join the test's transaction rather than
 * controlling its own, and the rollback being verified would be the test
 * framework's rather than the application's. <b>A test cannot verify a
 * transaction boundary from inside one.</b> Cleanup is therefore manual, in
 * {@link #cleanUp}.
 *
 * <p>That subtlety is the reason this file is worth more than its line count:
 * the obvious way to write it — copy the IT conventions, add
 * {@code @Transactional} — produces a test that passes whether or not the
 * annotation under test exists.
 */
@SpringBootTest
@ActiveProfiles({"jpa", "jpa-it"})
@EnabledIfSystemProperty(named = "it", matches = "true")
class UserRegistrationIT {

    @Autowired private UserService userService;
    @Autowired private WalletRepository walletRepository;
    @Autowired private JdbcClient jdbc;

    private static final String EMAIL = "d7.boundary@example.com";

    /**
     * Manual cleanup, in dependency order: wallets reference users, and the FK
     * has no {@code ON DELETE CASCADE} — deliberately, so that deleting a user
     * who still has a wallet FAILS rather than silently destroying their money.
     * That design decision is why these two statements cannot be reordered.
     */
    @BeforeEach
    void cleanUp() {
        jdbc.sql("DELETE FROM wallets WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'd7.%')").update();
        jdbc.sql("DELETE FROM users WHERE email LIKE 'd7.%'").update();
    }

    private CreateUserRequest request() {
        return new CreateUserRequest(EMAIL, "Boundary", "Test", "Pass1234#");
    }

    // =====================================================================
    //  The happy path: both rows, one transaction.
    // =====================================================================

    /**
     * Signup writes two rows, and both are really there.
     *
     * <p>Counted with raw SQL rather than through the repositories, because
     * asking Hibernate whether Hibernate saved something can be answered from
     * the persistence context without touching the database. The whole point is
     * what is COMMITTED, so the question goes to Postgres directly.
     */
    @Test
    void registrationCreatesAUserAndAWallet() {
        User user = userService.register(request());

        assertNotNull(user.getId(), "the user should have a database-assigned id");

        Long users = jdbc.sql("SELECT count(*) FROM users WHERE email = ?")
                .param(EMAIL).query(Long.class).single();
        Long wallets = jdbc.sql("SELECT count(*) FROM wallets WHERE user_id = ?")
                .param(user.getId()).query(Long.class).single();

        assertEquals(1L, users, "user row missing");
        assertEquals(1L, wallets, "WALLET ROW MISSING — registration wrote only one of two rows");
    }

    /**
     * The new wallet's defaults come from the database, not from Java.
     *
     * <p>Read back with raw SQL for a second reason beyond the one above: this
     * asserts the {@code NUMERIC(19,4)} scale, which only exists in Postgres.
     * {@code "0.0000"} rather than {@code "0"} is the column's doing, and it is
     * exactly the value that would fail an {@code assertEquals} against
     * {@code BigDecimal.ZERO} — the scale trap, observed against real storage
     * rather than argued about.
     */
    @Test
    void aNewWalletIsActiveWithAZeroBalanceAtScaleFour() {
        User user = userService.register(request());

        String balance = jdbc.sql("SELECT balance::text FROM wallets WHERE user_id = ?")
                .param(user.getId()).query(String.class).single();
        String status = jdbc.sql("SELECT status FROM wallets WHERE user_id = ?")
                .param(user.getId()).query(String.class).single();

        assertEquals("0.0000", balance,
                "NUMERIC(19,4) should return four decimal places");
        assertEquals("ACTIVE", status,
                "status must be stored as the NAME, not an ordinal — "
                        + "an integer here means @Enumerated defaulted to ORDINAL");
    }

    /**
     * The status column holds a word, which is the ORDINAL trap caught at the
     * storage layer.
     *
     * <p>Separate from the assertion above because this one is about the
     * COLUMN TYPE rather than the value: if {@code @Enumerated} were left to
     * default, Hibernate would want an integer column and
     * {@code ddl-auto=validate} would refuse to start — so in practice this
     * suite would fail at context startup rather than here. Written anyway,
     * because the day a {@code ddl-auto} setting slips, this is the assertion
     * that still notices.
     */
    @Test
    void statusIsStoredAsTextNotAnOrdinal() {
        userService.register(request());

        String dataType = jdbc.sql(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'wallets' AND column_name = 'status'")
                .query(String.class).single();

        assertEquals("character varying", dataType,
                "status column is not textual — @Enumerated(EnumType.STRING) missing?");
    }

    // =====================================================================
    //  THE POINT OF THE FILE: the rollback.
    // =====================================================================

    /**
     * <b>When the wallet cannot be created, the user must not survive either.</b>
     *
     * <p>Day-07's central claim, and the only test in the project that can make
     * it.
     *
     * <p>The failure is injected at the database level — a trigger-free,
     * deterministic way to make the second INSERT fail without touching
     * application code. A {@code CHECK} constraint is added to {@code wallets}
     * that rejects any row for an email starting {@code d7.rollback}, the
     * signup is attempted, and the user table is then counted.
     *
     * <p>Doing it in the database rather than by mocking {@code WalletService}
     * matters: a mock would prove that Spring rolls back when a mock throws,
     * which is a fact about Mockito. This proves that a REAL constraint
     * violation on the second write removes the first, which is the production
     * failure mode.
     */
    @Test
    void whenWalletCreationThrowsMidTransactionTheUserRowIsGone() {
        String email = "d7.rollback@example.com";
        jdbc.sql("DELETE FROM wallets WHERE user_id IN (SELECT id FROM users WHERE email = ?)")
                .param(email).update();
        jdbc.sql("DELETE FROM users WHERE email = ?").param(email).update();

        // Make every wallet INSERT fail, for everyone, for the duration of this
        // test. Crude and effective — and removed in the finally block so a
        // failure here cannot poison the rest of the suite.
        jdbc.sql("ALTER TABLE wallets ADD CONSTRAINT d7_force_failure CHECK (false)").update();
        try {
            assertThrows(Exception.class,
                    () -> userService.register(
                            new CreateUserRequest(email, "Roll", "Back", "Pass1234#")),
                    "the wallet insert should have failed");

            Long users = jdbc.sql("SELECT count(*) FROM users WHERE email = ?")
                    .param(email).query(Long.class).single();

            assertEquals(0L, users,
                    "THE USER SURVIVED A FAILED WALLET INSERT. "
                            + "@Transactional on UserService.register is missing, "
                            + "bypassed by self-invocation, or defeated by a catch block. "
                            + "This user can log in and can never hold money.");
        } finally {
            jdbc.sql("ALTER TABLE wallets DROP CONSTRAINT d7_force_failure").update();
        }
    }

    // =====================================================================
    //  The UNIQUE constraint — the guarantee behind @OneToOne.
    // =====================================================================

    /**
     * One user cannot have two wallets, and the database is what says so.
     *
     * <p>{@code WalletService.createFor} has an {@code existsByUserId} guard,
     * but that guard is a look-then-act and loses a race. This test bypasses
     * the service entirely and goes at the repository, because the question is
     * not "does the service check?" but "is the rule enforceable at all?".
     *
     * <p>This is what makes {@code @OneToOne} on {@code Wallet.user} a true
     * statement rather than a hopeful one — and it is a guarantee
     * {@code InMemoryWalletRepository} cannot reproduce, which is stated at
     * length in that class.
     */
    @Test
    void aSecondWalletForTheSameUserIsRefusedByTheDatabase() {
        User user = userService.register(request());

        assertThrows(Exception.class, () -> {
            walletRepository.save(new Wallet(user));
            walletRepository.flush();
        }, "the UNIQUE constraint on wallets.user_id did not fire");
    }

    /**
     * The balance cannot go negative, even if every Java guard is bypassed.
     *
     * <p>Written as raw SQL on purpose. {@code Wallet.debit} refuses an
     * overdraft, but that check races and is application code. This asserts the
     * constraint that cannot be raced — the backstop that still holds when a
     * future code path, a scheduled job or a manual UPDATE forgets.
     *
     * <p><b>Never let the only copy of a correctness guarantee live in
     * application code.</b> Application code races; constraints do not.
     */
    @Test
    void theDatabaseRefusesANegativeBalance() {
        User user = userService.register(request());

        assertThrows(Exception.class,
                () -> jdbc.sql("UPDATE wallets SET balance = -0.01 WHERE user_id = ?")
                        .param(user.getId()).update(),
                "wallets_balance_non_negative did not fire");
    }

    /**
     * The status CHECK constraint rejects a value the enum does not have.
     *
     * <p>The mirror of the ORDINAL argument: because the column stores names,
     * a typo or an unknown state is refused by Postgres. Had it stored
     * integers, no such constraint could be written and {@code 7} would be as
     * acceptable as {@code 1}.
     */
    @Test
    void theDatabaseRefusesAnUnknownStatus() {
        User user = userService.register(request());

        assertThrows(Exception.class,
                () -> jdbc.sql("UPDATE wallets SET status = 'SUSPENDED' WHERE user_id = ?")
                        .param(user.getId()).update(),
                "wallets_status_valid did not fire");
    }

    // =====================================================================
    //  Deposits through the service, against real storage.
    // =====================================================================

    /**
     * A deposit is actually persisted — dirty checking, not a {@code save}.
     *
     * <p>Read back with {@code JdbcClient} rather than through the repository,
     * for the reason {@code UserService.updateProfile} documents: asking
     * Hibernate whether Hibernate wrote something can be answered from the
     * persistence context without a round trip. If {@code @Transactional} were
     * missing from {@code WalletService.deposit}, the entity would be detached,
     * {@code credit} would mutate a plain object, nothing would be written —
     * and a test asserting on the RETURNED object would still be green.
     */
    @Test
    void aDepositIsWrittenToTheDatabase() {
        User user = userService.register(request());

        walletServiceDeposit(user.getId(), new BigDecimal("10.50"));

        String balance = jdbc.sql("SELECT balance::text FROM wallets WHERE user_id = ?")
                .param(user.getId()).query(String.class).single();

        assertEquals("10.5000", balance,
                "deposit not persisted — is @Transactional missing from deposit()?");
    }

    @Autowired private WalletService walletService;

    private void walletServiceDeposit(Long userId, BigDecimal amount) {
        walletService.deposit(userId, amount);
    }

    /**
     * Exact decimal arithmetic survives a round trip through Postgres.
     *
     * <p>Two deposits that would sum to {@code 0.30000000000000004} as doubles.
     * The assertion is on the string the database returns, so there is no Java
     * arithmetic in the comparison at all.
     */
    @Test
    void repeatedDepositsAccumulateExactly() {
        User user = userService.register(request());

        walletServiceDeposit(user.getId(), new BigDecimal("0.10"));
        walletServiceDeposit(user.getId(), new BigDecimal("0.20"));

        String balance = jdbc.sql("SELECT balance::text FROM wallets WHERE user_id = ?")
                .param(user.getId()).query(String.class).single();

        assertEquals("0.3000", balance, "exact arithmetic lost across the round trip");
    }

    /**
     * A frozen wallet refuses a deposit, and the balance is unchanged in the
     * database afterwards.
     *
     * <p>The second half is the part worth having. A 409 that nonetheless
     * credited the account is indistinguishable from a correct 409 if only the
     * exception is asserted — the same reason Day-06's live matrix checked psql
     * row counts rather than status codes.
     */
    @Test
    void aFrozenWalletRefusesADepositAndTheBalanceDoesNotMove() {
        User user = userService.register(request());
        walletServiceDeposit(user.getId(), new BigDecimal("25.00"));
        walletService.freeze(user.getId());

        assertThrows(Exception.class,
                () -> walletServiceDeposit(user.getId(), new BigDecimal("5.00")));

        String balance = jdbc.sql("SELECT balance::text FROM wallets WHERE user_id = ?")
                .param(user.getId()).query(String.class).single();

        assertEquals("25.0000", balance,
                "the deposit was refused but the money moved anyway");
        assertEquals(WalletStatus.FROZEN,
                walletService.getByUserId(user.getId()).getStatus());
    }
}
