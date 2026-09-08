package com.persona;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The classic Spring Boot smoke test.
 *
 * <p>It looks like it tests nothing - there is no assertion. But
 * {@code @SpringBootTest} boots the entire application context, so this test fails
 * whenever a bean is misconfigured, a dependency is missing, or two beans collide.
 * It is the cheapest possible "does the app actually start?" check, and it is the
 * first thing that breaks when a later slice wires something up wrong.
 */
@SpringBootTest
class PersonaApplicationTests {

    @Test
    void contextLoads() {
    }
}
