package com.persona.controller;

import java.security.Principal;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.persona.dto.DepositRequest;
import com.persona.dto.WalletResponse;
import com.persona.model.User;
import com.persona.service.UserService;
import com.persona.service.WalletService;

/**
 * HTTP for wallets. Day-07.
 *
 * <h2>How little is in here, and why that is the measure of the day</h2>
 *
 * <p>Not one method below contains a business rule. There is no status check,
 * no amount check, no balance arithmetic — those live in {@code Wallet}, where
 * they cannot be bypassed. What is left is the only thing a controller should
 * ever do: turn an HTTP request into a service call, and a domain exception
 * into a status code.
 *
 * <p>The version of this class that would have been easy to write looks like
 * this, and is the mistake the day is built around:
 *
 * <pre>
 *     if (wallet.getStatus() == WalletStatus.FROZEN) {
 *         return ResponseEntity.status(409).build();
 *     }
 * </pre>
 *
 * <p>It works. It also means the rule protects <em>this endpoint</em> rather
 * than the money: the next caller — a scheduled job, an admin tool, Day-08's
 * transfer — does not pass through here, and the check is simply absent for
 * them. Rules placed at the transport layer are only as good as the number of
 * transports, which is a number that always grows.
 *
 * <h2>Addressing wallets by their owner, not by their own id</h2>
 *
 * <p>Every route below is {@code /wallets/me} or {@code /wallets/{email}} —
 * never {@code /wallets/{walletId}}. A wallet is not independently
 * addressable, and that is a security property rather than a style choice: an
 * endpoint taking a wallet id invites an IDOR, where a user increments the
 * number and reads somebody else's balance. The authorization check would have
 * to be "load the wallet, then compare its owner to the caller" — a check that
 * is easy to forget and silent when forgotten.
 *
 * <p>Identifying the wallet BY its owner makes the authorization question the
 * same as the routing question. {@code /wallets/me} cannot address another
 * user's wallet at all; there is no parameter in which to put one.
 */
@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    /**
     * Needed to turn the authenticated email into a user id.
     *
     * <p>{@code Principal.getName()} is an email, because that is what
     * {@code JwtAuthenticationFilter} puts in the authentication. Wallets are
     * keyed by user id. Something has to bridge the two, and doing it here
     * keeps {@code WalletService} from needing a {@code UserRepository} —
     * which would be the start of every service depending on every other.
     */
    private final UserService userService;

    public WalletController(WalletService walletService, UserService userService) {
        this.walletService = walletService;
        this.userService = userService;
    }

    /**
     * The caller's own wallet.
     *
     * <p>No {@code @PreAuthorize} is needed and none is written: the route has
     * no parameter identifying a wallet, so there is no way to ask for one that
     * is not yours. {@code SecurityConfig}'s {@code anyRequest().authenticated()}
     * supplies the only check that matters — that there is a caller at all.
     *
     * <p>Note this is readable while FROZEN and while CLOSED, deliberately. A
     * user whose wallet has been suspended must still be able to see their own
     * money; hiding it turns a support ticket into a complaint to a regulator.
     * The status travels in the response so a client can explain the situation
     * rather than guess at it.
     */
    @GetMapping("/me")
    public WalletResponse myWallet(Principal principal) {
        User user = userService.getByEmail(principal.getName());
        return WalletResponse.from(walletService.getByUserId(user.getId()));
    }

    /**
     * Adds dummy money to the caller's own wallet.
     *
     * <p>{@code @Valid} is what makes {@link DepositRequest}'s annotations run
     * — without it they are decoration, which is a failure mode this project
     * has already paid for once. With it, a null, zero, negative,
     * over-precise or absurdly large amount is a 400 naming the field, and
     * never reaches the service.
     *
     * <p>A POST rather than a PATCH on the balance, because this is an
     * operation rather than an edit. "Deposit 50" is not the same statement as
     * "the balance is now 50" — the second is what a PATCH would mean, and it
     * would be an unguarded way to set money to an arbitrary value. The verb
     * keeps the guarantee.
     *
     * <p>Returns the updated wallet rather than 204, so a client can render the
     * new balance without a second round trip — and so it sees the exact value
     * the server stored, rather than the one it predicted by adding locally.
     */
    @PostMapping("/me/deposit")
    public WalletResponse deposit(Principal principal,
                                  @Valid @RequestBody DepositRequest request) {
        User user = userService.getByEmail(principal.getName());
        return WalletResponse.from(
                walletService.deposit(user.getId(), request.amount()));
    }

    /**
     * Suspends a user's wallet. Admin only.
     *
     * <p>{@code hasRole('ADMIN')} rather than the self-or-admin rule used for
     * deletion: freezing your own wallet is not a thing a user should be able
     * to do, because the state exists for the operator's benefit during an
     * investigation, not the account holder's. A user who could unfreeze
     * themselves would make the whole state meaningless.
     *
     * <p>Addressed by email rather than by wallet id for the reason in the
     * class comment — and note that an admin route is exactly where an IDOR
     * matters least and is still worth closing, because admin credentials are
     * the ones worth stealing.
     */
    @PostMapping("/{email}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    public WalletResponse freeze(@PathVariable String email) {
        User user = userService.getByEmail(email);
        return WalletResponse.from(walletService.freeze(user.getId()));
    }

    /** Reverses {@link #freeze}. Admin only, for the same reason. */
    @PostMapping("/{email}/unfreeze")
    @PreAuthorize("hasRole('ADMIN')")
    public WalletResponse unfreeze(@PathVariable String email) {
        User user = userService.getByEmail(email);
        return WalletResponse.from(walletService.unfreeze(user.getId()));
    }

    /**
     * Any user's wallet. Admin only.
     *
     * <p>Exists so support can answer "what is this account's balance" without
     * a database client. The {@code @PreAuthorize} is the entire protection
     * here, which is why this is the one route in the class where a missing
     * annotation would be a real breach rather than a tidiness problem — and
     * why the live verification matrix tests it as a USER, not only as an
     * ADMIN. A route that is only ever tested with credentials that pass
     * proves nothing about the ones that should not.
     */
    @GetMapping("/{email}")
    @PreAuthorize("hasRole('ADMIN')")
    public WalletResponse walletOf(@PathVariable String email) {
        User user = userService.getByEmail(email);
        return WalletResponse.from(walletService.getByUserId(user.getId()));
    }
}
