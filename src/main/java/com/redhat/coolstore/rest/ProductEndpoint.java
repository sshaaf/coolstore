package com.redhat.coolstore.rest;

import java.io.Serializable;
import java.util.List;

import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.service.ProductService;

@ApplicationScoped
@Blocking
@Path("/products")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ProductEndpoint implements Serializable {

    /**
     *
     */
    private static final long serialVersionUID = -7227732980791688773L;

    @Inject
    private ProductService pm;


    @GET
    @Path("/")
    public List<Product> listAll() {
        return pm.getProducts();
    }

    @GET
    @Path("/{itemId}")
    public Product getProduct(@PathParam("itemId") String itemId) {
        return pm.getProductByItemId(itemId);
    }

}
