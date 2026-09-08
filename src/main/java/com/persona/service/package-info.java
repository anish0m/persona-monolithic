/**
 * <h2>Layer 2 - business logic.</h2>
 *
 * The rules of persona live here: "email must be unique", "username is derived from
 * the name", "you cannot delete a profile that does not exist".
 *
 * <p>This package knows about {@code model} and {@code repository}. It must NOT know
 * about HTTP - no {@code HttpServletRequest}, no status codes, no JSON. If the app
 * later grows a CLI or a scheduled job, they reuse these services unchanged.
 *
 * <p>This is where most of the interesting code ends up. A fat controller or a fat
 * repository is usually a sign that logic leaked out of this package.
 */
package com.persona.service;
