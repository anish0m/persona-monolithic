package com.persona;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The single entry point of the persona monolith.
 *
 * <p>{@code @SpringBootApplication} is three annotations in one:
 * <ul>
 *   <li>{@code @SpringBootConfiguration} - this class is a source of bean definitions.</li>
 *   <li>{@code @EnableAutoConfiguration} - look at what is on the classpath and configure
 *       it automatically. Because spring-boot-starter-web is present, Spring Boot starts
 *       an embedded Tomcat on port 8080 without us writing a line of server code.</li>
 *   <li>{@code @ComponentScan} - scan THIS package and everything below it for
 *       {@code @Component}, {@code @Service}, {@code @Repository}, {@code @RestController}.</li>
 * </ul>
 *
 * <p>That last point is why this class sits in {@code com.persona} and every other package
 * ({@code com.persona.model}, {@code com.persona.service}, ...) sits underneath it.
 * Move this class deeper and Spring silently stops finding your beans.
 */
@SpringBootApplication
public class PersonaApplication {

    public static void main(String[] args) {
        SpringApplication.run(PersonaApplication.class, args);
    }
}
