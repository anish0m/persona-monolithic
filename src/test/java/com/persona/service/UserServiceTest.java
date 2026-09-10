package com.persona.service;

import com.persona.exception.DuplicateEmailException;
import com.persona.exception.UserNotFoundException;
import com.persona.model.User;
import com.persona.repository.InMemoryUserRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link UserService}.
 *
 * <p>Note what is absent: {@code @SpringBootTest}, {@code @Autowired}, any Spring
 * annotation at all. The service is built with {@code new} and handed a repository
 * by hand. That is not a shortcut around Spring — it is the payoff for constructor
 * injection. These tests start no application context, so they run in milliseconds
 * rather than seconds, and a failure here can only mean the business logic is
 * wrong, never that the wiring is.
 *
 * <p>Had {@code UserService} used field injection, none of these tests could exist
 * in this form: a private field with no setter cannot be populated without Spring,
 * so every test would have to boot the framework to test one {@code if}.
 */
class UserServiceTest {

    private final UserService service = new UserService(new InMemoryUserRepository());

    private User anishom() {
        return new User("khi0ne@example.com", "Anishom", "Frost", "Pass1234#");
    }

    @Test
    void registeringStoresTheUser() {
        service.register(anishom());

        assertEquals(1, service.count());
        assertTrue(service.findByEmail("khi0ne@example.com").isPresent());
    }

    @Test
    void registerReturnsTheSavedUser() {
        User saved = service.register(anishom());

        // Pointless-looking today, since the caller already holds the object. From
        // Day-03 the database generates the id and this return value is the only
        // place it exists — establishing the signature now avoids editing callers.
        assertEquals(anishom(), saved);
    }

    @Test
    void secondSignupWithTheSameEmailIsRejected() {
        service.register(anishom());

        DuplicateEmailException thrown =
                assertThrows(DuplicateEmailException.class, () -> service.register(anishom()));

        // The exception carries the email as data, not only inside a message string.
        // Day-09 builds a 409 response from this without parsing English.
        assertEquals("khi0ne@example.com", thrown.getEmail());
        assertEquals(1, service.count());
    }

    @Test
    void aDifferentEmailIsNotADuplicate() {
        service.register(anishom());
        service.register(new User("lincoln@example.com", "Lincoln", "Frost", "Pass1234#"));

        assertEquals(2, service.count());
    }

    @Test
    void missingUserIsEmptyNotAnError() {
        // Absence is a normal answer here, not a failure: "is this email free?"
        // expects to find nothing most of the time.
        assertTrue(service.findByEmail("nobody@example.com").isEmpty());
    }

    @Test
    void getByEmailThrowsWhenMissing() {
        UserNotFoundException thrown =
                assertThrows(UserNotFoundException.class, () -> service.getByEmail("nobody@example.com"));

        assertEquals("nobody@example.com", thrown.getEmail());
    }

    @Test
    void deletingRemovesTheUser() {
        service.register(anishom());
        service.deleteByEmail("khi0ne@example.com");

        assertEquals(0, service.count());
        assertTrue(service.findByEmail("khi0ne@example.com").isEmpty());
    }

    @Test
    void deletingAMissingUserThrows() {
        assertThrows(UserNotFoundException.class, () -> service.deleteByEmail("nobody@example.com"));
    }

    @Test
    void anEmailFreedByDeletionCanBeRegisteredAgain() {
        service.register(anishom());
        service.deleteByEmail("khi0ne@example.com");
        service.register(anishom());

        assertEquals(1, service.count());
    }
}
