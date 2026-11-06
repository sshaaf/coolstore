package com.redhat.coolstore.rest;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import org.jboss.logging.Logger;

/**
 * Turns uncaught exceptions into a stable JSON error body.
 *
 * <p>The monolith let exceptions escape from {@code CartEndpoint.add} (pricing failures) and the
 * container rendered an HTML error page possibly containing a stack trace. Clients now always get
 * {@code {"error": "...", "message": "..."}} with the original status for
 * {@link WebApplicationException} and {@code 500} otherwise.
 */
@Provider
public class ApiExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger LOG = Logger.getLogger(ApiExceptionMapper.class);

    @Override
    public Response toResponse(Exception exception) {
        if (exception instanceof WebApplicationException web) {
            return web.getResponse();
        }
        LOG.error("Unhandled error while serving a request", exception);
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        String body = "{\"error\":\"" + escape(exception.getClass().getSimpleName())
                + "\",\"message\":\"" + escape(message) + "\"}";
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
