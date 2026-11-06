package com.redhat.coolstore.rest;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.service.ProductService;

/**
 * Catalogue endpoints. Paths and media types are unchanged from the JAX-RS 2.0 version
 * ({@code quarkus.rest.path=/services} keeps the {@code /services} prefix that
 * {@code @ApplicationPath} used to provide).
 *
 * <p>{@code GET /products/{itemId}} returns {@code null} for an unknown SKU, which JAX-RS turns into
 * {@code 204 No Content} - the same status the monolith returned.
 */
@ApplicationScoped
@Path("/products")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ProductEndpoint {

    @Inject
    ProductService productService;

    @GET
    public List<Product> listAll() {
        return productService.getProducts();
    }

    @GET
    @Path("/{itemId}")
    public Product getProduct(@PathParam("itemId") String itemId) {
        return productService.getProductByItemId(itemId);
    }
}
