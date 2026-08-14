package com.dcbate.tradingplatform.exception;

/**
 * Thrown by {@code AuthServiceImpl.login} for either a missing email or a wrong password; mapped
 * to 401. Deliberately the same message and exception for both cases — telling a caller "that
 * email isn't registered" versus "wrong password" is a user-enumeration leak.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
