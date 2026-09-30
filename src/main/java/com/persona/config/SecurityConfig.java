package com.persona.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Where persona decides who may call what. <b>Day-06.</b>
 *
 * <p><b>Through Day-05 this class did not enable Spring Security at all</b> —
 * it supplied a {@link PasswordEncoder} and nothing else, because the project
 * depended on {@code spring-security-crypto} (a plain library, no filters) and
 * deliberately not on {@code spring-boot-starter-security}. That comment is
 * now history, and the reason it was written is exactly why this file changed
 * today rather than earlier: the starter installs a filter chain that
 * intercepts every request, which was noise on Day-05 and is the entire
 * subject on Day-06.
 *
 * <h2>Why a @Configuration class rather than a field in UserService</h2>
 *
 * <p>{@code new BCryptPasswordEncoder()} inside the service would work and is
 * what the shorter tutorials do. Three things are bought by declaring it as a
 * bean instead:
 *
 * <ul>
 *   <li><b>The cost factor is decided in one place.</b> It is a tuning
 *       parameter that will change as hardware gets faster, and a value
 *       scattered across call sites drifts.</li>
 *   <li><b>The service depends on the interface.</b> {@code UserService} takes
 *       {@link PasswordEncoder}, not {@code BCryptPasswordEncoder}, so a move
 *       to argon2 is an edit to this file alone — the same reason Day-03
 *       extracted {@code UserRepository} before a second implementation
 *       existed.</li>
 *   <li><b>Tests can substitute a fast one.</b> Which matters more than it
 *       sounds; see below.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    /**
     * The filter chain: what is public, what needs a token, and what needs a
     * role.
     *
     * <p>This bean <b>replaces</b> Boot's default chain entirely. The default
     * secures every endpoint behind a generated password printed to the
     * startup log — useful for a five-minute demo, wrong for anything else,
     * and the reason adding the starter without writing this method turns a
     * working application into a 401 machine.
     *
     * <h2>The rules, and why each one</h2>
     *
     * <pre>
     *   POST /users        permitAll   signup: there is no token yet, by definition
     *   POST /auth/login   permitAll   login: the endpoint that ISSUES the token
     *   GET  /users        ADMIN only  listing every account is an admin action
     *   anything else      authenticated
     * </pre>
     *
     * <p><b>The default is {@code authenticated()}, deliberately.</b>
     * {@code anyRequest()} comes last and catches everything not named above,
     * so a new endpoint added on Day-07 is protected without anyone
     * remembering to protect it. The opposite default — {@code permitAll} at
     * the bottom — fails open: every future endpoint is public until someone
     * notices. <b>Order matters and first match wins</b>, which is why the
     * specific rules are listed before the catch-all; reverse them and
     * {@code anyRequest()} swallows everything.
     *
     * <p>{@code GET /users} is the one genuinely
     * <em>authorization</em>-flavoured rule here — the caller is known and
     * still may not proceed. It is the AuthN/AuthZ distinction made concrete:
     * a logged-in normal user gets <b>403</b> (we know who you are, no), while
     * a caller with no token gets <b>401</b> (we do not know who you are).
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // CSRF protection OFF, and this is safe ONLY because persona's
                // API is stateless and token-based.
                //
                // CSRF attacks work by making a victim's BROWSER send a request
                // that carries its credentials automatically — which is what a
                // session cookie does, and precisely what an Authorization
                // header does not. A malicious page cannot make the browser
                // attach a header it does not know. Turning this off on a
                // cookie-authenticated app would be a genuine vulnerability;
                // here it removes a token exchange that protects nothing.
                //
                // Day-09 adds server-rendered Bootstrap pages. If those ever
                // authenticate by cookie, this line must be revisited — noted
                // here because that is the day the reasoning above stops
                // holding.
                .csrf(csrf -> csrf.disable())

                // Never create an HttpSession. Without this Spring Security
                // would happily create one and store the SecurityContext in
                // it, which quietly re-introduces exactly the server-side
                // state the JWT was chosen to avoid — and makes the app
                // unable to scale horizontally without sticky sessions.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // ERROR DISPATCHES BYPASS AUTHORIZATION, and leaving
                        // this out produced a genuinely confusing bug that no
                        // test caught.
                        //
                        // When authorization denies a request, Spring responds
                        // 403 and then Boot FORWARDS internally to /error to
                        // build the body. That forward re-enters this filter
                        // chain as a fresh, anonymous request — so /error was
                        // itself being denied, the authenticationEntryPoint
                        // fired, and it overwrote the already-correct 403 with
                        // a 401. A valid token looked exactly like no token.
                        //
                        // The security log is what actually said so:
                        //     AccessDeniedHandlerImpl: Responding with 403
                        //     FilterChainProxy: Securing GET /error
                        // The authorization decision had been right all along;
                        // the response was being rewritten after the fact.
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR,
                                jakarta.servlet.DispatcherType.FORWARD).permitAll()

                        .requestMatchers(HttpMethod.POST, "/users").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/users").hasRole("ADMIN")
                        .anyRequest().authenticated())

                // The JWT filter runs BEFORE the username/password filter.
                //
                // Not because persona uses form login — it does not — but
                // because UsernamePasswordAuthenticationFilter is the standard
                // positional anchor in the chain, and what actually matters is
                // that the SecurityContext is populated before the
                // authorization rules above are evaluated. Register this
                // filter after them and every request is anonymous at the
                // moment the decision is made: a valid token would return 401,
                // which looks like a broken token and is really a broken
                // ordering.
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)

                // No .httpBasic() and no .formLogin(). Both would add a SECOND
                // way to authenticate, and a second way in is a second thing
                // to secure, rate-limit and reason about. POST /auth/login is
                // the only door.

                // 401 FOR UNAUTHENTICATED, and this had to be configured —
                // found by running it, not by a test.
                //
                // Without this block, a request with no token (or a forged
                // one) returned 403 Forbidden, not 401 Unauthorized. The
                // reason is that Spring picks an AuthenticationEntryPoint
                // from the login mechanisms configured, and with neither
                // httpBasic nor formLogin there is nothing that knows how to
                // issue a challenge — so it falls through to the access-denied
                // path, which is 403.
                //
                // That is exactly the distinction this day is about, inverted:
                // 401 means "I do not know who you are, credentials may help",
                // 403 means "I know who you are and the answer is still no".
                // Returning 403 to an anonymous caller tells them retrying
                // with a token is pointless, which is false.
                //
                // Note the two handlers are genuinely different paths:
                // authenticationEntryPoint handles "not authenticated at all",
                // accessDeniedHandler handles "authenticated, insufficient
                // authority" — which is what GET /users as a USER hits, and
                // which was already correct at 403.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType("application/problem+json");
                            response.getWriter().write("""
                                    {"type":"about:blank","title":"Unauthorized",\
                                    "status":401,\
                                    "detail":"Authentication required"}""");
                        }))

                .build();
    }

    /**
     * BCrypt at the library default cost of 10.
     *
     * <h2>Why the algorithm is deliberately slow</h2>
     *
     * <p>Every other decision in this project made something faster. This one
     * makes something slow on purpose, and the slowness <em>is</em> the
     * security.
     *
     * <p>An attacker who steals the {@code users} table does not attack the
     * algorithm; they guess. On a consumer GPU, SHA-256 runs at roughly ten
     * billion hashes per second, so the ten million most common passwords can
     * be tried against an entire table in well under a second. BCrypt at cost
     * 10 runs at roughly ten thousand per second, and the same list takes weeks
     * <em>per password</em>.
     *
     * <p>SHA-256 is not a bad hash — it is a bad <em>password</em> hash,
     * because it was designed to be fast, which is exactly right for checksums
     * and exactly wrong here.
     *
     * <pre>
     *   cost 10  ->  2^10 = 1024 rounds   ~  50-100 ms
     *   cost 11  ->  2^11 = 2048 rounds   ~ 100-200 ms
     *   cost 12  ->  2^12 = 4096 rounds   ~ 200-400 ms
     * </pre>
     *
     * <p>Each increment <b>doubles</b> the work: unnoticeable to one person
     * logging in, devastating to someone doing it ten billion times. Hardware
     * gets faster every year and BCrypt has a dial to compensate. <b>That dial
     * is the feature</b>, and SHA-256 does not have one.
     *
     * <h2>Salt, and why it is not a secret</h2>
     *
     * <p>Two users with the same password must not produce the same digest, or
     * an attacker who spots the collision has cracked two accounts for one
     * guess, and precomputed rainbow tables crack the whole column at once. A
     * salt is random bytes mixed in before hashing, unique per row.
     *
     * <p>The salt is stored in plaintext, inside the hash string itself. That
     * feels wrong and is not: its only job is to stop work being shared between
     * rows, and it does that whether or not the attacker can read it.
     *
     * <pre>
     *   $2a$10$N9qo8uLOickgx2ZMRZoMye IjZAgcfl7p92ldGxad68LJZdL17lhWy
     *    |   |  |                      |
     *    |   |  +- salt, 22 chars      +- hash, 31 chars
     *    |   +---- cost factor
     *    +-------- algorithm version
     *
     *   One string. Always exactly 60 characters -> VARCHAR(60) in V2.
     * </pre>
     *
     * <p>There is no {@code salt} column and no salt is generated by hand.
     * Note also that the <em>cost factor is stored in the output</em>, which is
     * what lets the cost be raised later without invalidating existing hashes:
     * {@code matches} reads the cost out of the stored string rather than
     * assuming the current setting.
     *
     * <h2>The two methods, and the test everyone gets wrong</h2>
     *
     * <pre>
     *   encode(raw)            -> a NEW hash, DIFFERENT on every call
     *   matches(raw, stored)   -> reads salt + cost out of `stored`,
     *                             re-hashes `raw` the same way, compares
     * </pre>
     *
     * <p>{@code encode(x).equals(encode(x))} is <b>false</b>, always, because
     * each call draws a new random salt. This is correct behaviour and it is
     * the first test most people write and cannot explain. Comparison is done
     * with {@code matches}, never with {@code equals}.
     *
     * <h2>The cost of the cost factor, in the test suite</h2>
     *
     * <p>Cost 10 is ~50-100ms per hash. A suite that registers a user in thirty
     * tests pays three seconds for it. That is tolerable now and is the kind of
     * thing that quietly becomes a two-minute suite by Day-14.
     *
     * <p>Left at the default deliberately, because the alternative — lowering
     * the cost in tests — means the tests no longer exercise the configuration
     * that ships. Recorded as dated debt: if the suite becomes slow, the fix is
     * a test-profile bean at cost 4, and the thing to check when doing it is
     * that at least one test still runs at the production cost.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
