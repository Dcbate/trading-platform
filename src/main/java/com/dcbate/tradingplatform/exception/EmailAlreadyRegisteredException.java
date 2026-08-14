package com.dcbate.tradingplatform.exception;

/** Thrown by {@code AuthServiceImpl.signup} when the email is already tied to an account; mapped to 409. */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(String email) {
        super("An account already exists for email: " + email);
    }
}
