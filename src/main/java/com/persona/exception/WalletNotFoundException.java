package com.persona.exception;

/**
 * Thrown when a user's wallet is looked up and is not there. Day-07.
 *
 * <p>Unchecked, for the transaction-rollback reason on
 * {@link WalletNotActiveException}.
 *
 * <p>Maps to <b>404</b>, not 409: the resource genuinely does not exist, as
 * opposed to existing in a state that refuses the operation. Compare
 * {@link WalletNotActiveException}, which is 409 precisely because the wallet
 * is there and is saying no.
 *
 * <h2>When this should be impossible</h2>
 *
 * <p>Registration creates a wallet in the same transaction as the user, so
 * every user has one and this exception should never reach a caller who has
 * authenticated successfully. It is written anyway, and the reason is worth
 * stating: <b>"impossible" here means "impossible if the transaction boundary
 * on {@code UserService.register} is correct".</b>
 *
 * <p>If that annotation is ever removed, or defeated by self-invocation, or
 * defeated by someone catching the exception inside the method, this is the
 * exception that starts appearing in production — a user who exists, can log
 * in, and has no wallet. So its presence in a log is not merely a 404; it is
 * the signature of a broken boundary, which is why it names the user id.
 */
public class WalletNotFoundException extends RuntimeException {

    private final Long userId;

    public WalletNotFoundException(Long userId) {
        super("no wallet for user " + userId);
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}
