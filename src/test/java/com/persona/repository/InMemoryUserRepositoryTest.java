package com.persona.repository;

import com.persona.exception.DuplicateEmailException;
import com.persona.exception.UserNotFoundException;
import com.persona.model.User;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryUserRepositoryTest {

    private User anishom() {
        return new User("khi0ne@example.com", "Anishom", "Frost", "Pass1234#");
    }

    @Test
    void sameEmailMeansSamePerson() {
        User one = anishom();
        User two = new User("khi0ne@example.com", "Different", "Name", "Other9999#");

        // Different objects, different names, different passwords — same person,
        // because email is what identity means here.
        assertEquals(one, two);
    }

    @Test
    void differentEmailMeansDifferentPeople() {
        assertNotEquals(anishom(), new User("other@example.com", "Anishom", "Frost", "Pass1234#"));
    }

    @Test
    void equalUsersAreFoundInAHashSet() {
        // This is the test that fails if hashCode is omitted while equals is
        // overridden: contains() returns false even though the two are equal.
        Set<User> set = new HashSet<>();
        set.add(anishom());

        assertTrue(set.contains(anishom()));
        set.add(anishom());
        assertEquals(1, set.size());
    }

    @Test
    void savedUserCanBeFound() {
        InMemoryUserRepository repo = new InMemoryUserRepository();
        repo.save(anishom());

        assertTrue(repo.findByEmail("khi0ne@example.com").isPresent());
        assertEquals(1, repo.count());
    }

    @Test
    void missingUserIsEmptyNotAnError() {
        InMemoryUserRepository repo = new InMemoryUserRepository();
        assertTrue(repo.findByEmail("nobody@example.com").isEmpty());
    }

    @Test
    void getByEmailThrowsWhenMissing() {
        InMemoryUserRepository repo = new InMemoryUserRepository();

        UserNotFoundException thrown =
                assertThrows(UserNotFoundException.class, () -> repo.getByEmail("nobody@example.com"));

        // The email is available as data, not only inside the message string.
        assertEquals("nobody@example.com", thrown.getEmail());
    }

    @Test
    void secondSignupWithTheSameEmailIsRejected() {
        InMemoryUserRepository repo = new InMemoryUserRepository();
        repo.save(anishom());

        assertThrows(DuplicateEmailException.class, () -> repo.save(anishom()));
        assertEquals(1, repo.count());
    }

    @Test
    void deletingAMissingUserThrows() {
        InMemoryUserRepository repo = new InMemoryUserRepository();
        assertThrows(UserNotFoundException.class, () -> repo.deleteByEmail("nobody@example.com"));
    }

    @Test
    void findAllCannotBeUsedToMutateTheRepository() {
        InMemoryUserRepository repo = new InMemoryUserRepository();
        repo.save(anishom());

        // The returned collection is a copy, so the repository survives this.
        assertThrows(UnsupportedOperationException.class, () -> repo.findAll().clear());
        assertEquals(1, repo.count());
    }
}
