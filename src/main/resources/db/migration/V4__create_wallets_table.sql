-- ===========================================================================
--  V4 — the wallet. Day-07.
--
--  persona has no wallets table at all (PayNest got one on Day-03, in its V2).
--  So this migration creates the whole thing, where PayNest's Day-07 migration
--  only adds a status column to a table that already exists. Same lesson,
--  different starting point — and worth noticing that the SQL below is where
--  the Java decisions of the day are actually enforced.
-- ===========================================================================

CREATE TABLE wallets (

    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- -------------------------------------------------------------------
    --  THE LINE THAT MAKES @OneToOne TRUE RATHER THAN MERELY CLAIMED.
    --
    --  Three separate guarantees are packed into one column definition,
    --  and each maps to a different piece of Java:
    --
    --    REFERENCES users(id)  -> no wallet for a non-existent user
    --                             (Java: @JoinColumn)
    --    NOT NULL              -> no wallet without an owner
    --                             (Java: optional = false)
    --    UNIQUE                -> no user has two wallets
    --                             (Java: @OneToOne, NOT @ManyToOne)
    --
    --  The UNIQUE is the one that matters. Without it, @OneToOne in the
    --  entity is a claim the Java makes that nothing enforces: the database
    --  would happily accept a second wallet for user 7, and the mapping
    --  would then blow up at READ time with "query returned 2 results" —
    --  far away from the INSERT that actually caused it.
    --
    --  THE TELL, for when the right annotation is not obvious: do not
    --  reason about the objects. Look at the foreign key column for a
    --  UNIQUE constraint. Unique means @OneToOne; not unique means
    --  @ManyToOne. The schema answers the question.
    --
    --  DAY-17 NOTE: this FK is the thing that cannot survive the split.
    --  Once wallets live in wallet-service with its own database, there is
    --  no cross-database foreign key in PostgreSQL — the guarantee stops
    --  being enforced by one process and becomes something the application
    --  has to arrange. That is the concrete cost of independent deployment,
    --  and persona-microservice pays it today, not on Day-17.
    -- -------------------------------------------------------------------
    user_id BIGINT NOT NULL UNIQUE REFERENCES users(id),

    -- -------------------------------------------------------------------
    --  NEVER float/double, in any language. 0.1 + 0.2 = 0.30000000000000004
    --  under IEEE 754 — not a bug, the specified behaviour of binary
    --  fractions. 0.1 has no exact binary form, the same way 1/3 has no
    --  exact decimal form.
    --
    --  NUMERIC is exact decimal arithmetic: the database stores 0.30, not
    --  the nearest binary approximation to it.
    --
    --  The real cost is not the lost fraction. It is losing the ability to
    --  tell an arithmetic error from an attack: once totals drift on their
    --  own, a discrepancy proves nothing and the books cannot be audited.
    --  (Vancouver Stock Exchange, 1980s — the index drifted to roughly half
    --  its true value over 22 months. Nobody stole anything.)
    --
    --  (19,4) = 19 significant digits, 4 after the point. FOUR, not two, so
    --  an intermediate result (a 2.5% fee on 10.01 is 0.25025) keeps its
    --  precision and the rounding happens ONCE, at the end. Rounding at
    --  every step compounds the error.
    --
    --  The Java side must match this exactly: BigDecimal with
    --  precision = 19, scale = 4. A mismatch is a startup failure under
    --  ddl-auto=validate, which is the check working.
    -- -------------------------------------------------------------------
    balance NUMERIC(19,4) NOT NULL DEFAULT 0,

    -- -------------------------------------------------------------------
    --  VARCHAR, not an integer, and this is the @Enumerated(ORDINAL) trap
    --  written out in SQL.
    --
    --  Had the Java used the DEFAULT @Enumerated — which is ORDINAL — this
    --  column would hold 0, 1 and 2, and their meaning would live nowhere
    --  but the source-file ORDERING of a Java enum. Reorder that enum six
    --  months later and every frozen wallet silently becomes closed. No
    --  migration runs. Nothing warns. The existing data is reinterpreted
    --  underneath you.
    --
    --  Storing the name makes the column readable in psql, makes the enum
    --  safely reorderable, and makes the CHECK below possible at all —
    --  against ORDINAL integers that constraint cannot be written.
    -- -------------------------------------------------------------------
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- -------------------------------------------------------------------
    --  TABLE-LEVEL constraints: their own entries in the comma-separated
    --  list, because they are rules about the row rather than properties
    --  of a single column.
    --
    --  These are the BACKSTOP, not the mechanism. The mechanism is
    --  Wallet.credit(), which refuses a non-ACTIVE wallet and a
    --  non-positive amount before any SQL is generated. This CHECK is the
    --  second lock on the door: if any future code path forgets — a
    --  scheduled job, an admin tool, someone running an UPDATE by hand at
    --  2am — the database still refuses.
    --
    --  Deliberately belt and braces. Money is the place for it.
    -- -------------------------------------------------------------------
    CONSTRAINT wallets_balance_non_negative CHECK (balance >= 0),

    CONSTRAINT wallets_status_valid
        CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

-- No index on wallets.user_id: the UNIQUE constraint already created one.
-- Postgres implements UNIQUE with a unique B-tree index, so adding another
-- would be a second copy of the same structure, maintained on every write,
-- answering no query the first cannot.

COMMENT ON TABLE wallets IS
    'One wallet per user, enforced by the UNIQUE on user_id. Created in the same transaction as the user — see UserService.register.';

COMMENT ON COLUMN wallets.balance IS
    'Exact decimal. NEVER read into a float/double in any client. Serialised to JSON as a string.';

COMMENT ON COLUMN wallets.status IS
    'ACTIVE, FROZEN or CLOSED. Stored as the NAME, not an ordinal — see migration comment. CLOSED is terminal.';
