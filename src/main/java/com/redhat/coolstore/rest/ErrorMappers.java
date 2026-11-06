package com.redhat.coolstore.rest;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Structured error responses without changing the legacy status-code contract:
 * failures that used to escape to the container as HTTP 500 still return 500, but with a
 * JSON body and a correlated server-side log line.
 */
public final class ErrorMappers {

    private static final Logger log = Logger.getLogger(ErrorMappers.class.getName());

    private ErrorMappers() {
    }

    @Provider
    public static class GenericExceptionMapper implements ExceptionMapper<Throwable> {
        @Override
        public Response toResponse(Throwable throwable) {
            String correlationId = java.util.UUID.randomUUID().toString();
            log.log(Level.SEVERE, "Request failed [correlationId=" + correlationId + "]", throwable);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(body(throwable, correlationId, 500))
                    .build();
        }
    }

    @Provider
    public static class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {
        @Override
        public Response toResponse(WebApplicationException exception) {
            int status = exception.getResponse() == null
                    ? Response.Status.INTERNAL_SERVER_ERROR.getStatusCode()
                    : exception.getResponse().getStatus();
            if (status >= 500) {
                log.log(Level.SEVERE, "Web application error " + status, exception);
            }
            return Response.status(status)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(body(exception, null, status))
                    .build();
        }
    }

    private static Map<String, Object> body(Throwable throwable, String correlationId, int status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", throwable.getClass().getSimpleName());
        body.put("message", throwable.getMessage());
        body.put("status", status);
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        return body;
    }
}
