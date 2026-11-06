package com.redhat.coolstore.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.service.ShoppingCartService;

/**
 * Cart resource. Was {@code @SessionScoped} (and forced an HTTP session via index.jsp);
 * now stateless - cart identity is the {@code {cartId}} path parameter only.
 * Paths, verbs and status codes are unchanged.
 */
@ApplicationScoped
@Path("/cart")
public class CartEndpoint {

    private final ShoppingCartService shoppingCartService;

    @Inject
    public CartEndpoint(ShoppingCartService shoppingCartService) {
        this.shoppingCartService = shoppingCartService;
    }

    @GET
    @Path("/{cartId}")
    @Produces(MediaType.APPLICATION_JSON)
    public ShoppingCart getCart(@PathParam("cartId") String cartId) {
        return shoppingCartService.getShoppingCart(cartId);
    }

    @POST
    @Path("/checkout/{cartId}")
    @Produces(MediaType.APPLICATION_JSON)
    public ShoppingCart checkout(@PathParam("cartId") String cartId) {
        return shoppingCartService.checkOutShoppingCart(cartId);
    }

    @POST
    @Path("/{cartId}/{itemId}/{quantity}")
    @Produces(MediaType.APPLICATION_JSON)
    public ShoppingCart add(@PathParam("cartId") String cartId,
                            @PathParam("itemId") String itemId,
                            @PathParam("quantity") int quantity) {
        return shoppingCartService.addItem(cartId, itemId, quantity);
    }

    /**
     * Copy cart {@code tmpId} into {@code cartId}. Path-overlap with
     * {@code POST /{cartId}/{itemId}/{quantity}} is disambiguated by segment count.
     */
    @POST
    @Path("/{cartId}/{tmpId}")
    @Produces(MediaType.APPLICATION_JSON)
    public ShoppingCart set(@PathParam("cartId") String cartId,
                            @PathParam("tmpId") String tmpId) {
        return shoppingCartService.copyCart(cartId, tmpId);
    }

    @DELETE
    @Path("/{cartId}/{itemId}/{quantity}")
    @Produces(MediaType.APPLICATION_JSON)
    public ShoppingCart delete(@PathParam("cartId") String cartId,
                               @PathParam("itemId") String itemId,
                               @PathParam("quantity") int quantity) {
        return shoppingCartService.removeItem(cartId, itemId, quantity);
    }
}
