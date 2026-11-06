package com.redhat.coolstore.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.service.ProductService;

@ApplicationScoped
@Path("/products")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ProductEndpoint {

    private final ProductService productService;

    @Inject
    public ProductEndpoint(ProductService productService) {
        this.productService = productService;
    }

    @GET
    @Path("/")
    public List<Product> listAll() {
        return productService.getProducts();
    }

    @GET
    @Path("/{itemId}")
    public Product getProduct(@PathParam("itemId") String itemId) {
        return productService.getProductByItemId(itemId);
    }
}
