package com.redhat.coolstore.service;

/** Raised when the external cart store (Redis) cannot be reached or decoded. */
public class CartStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CartStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
