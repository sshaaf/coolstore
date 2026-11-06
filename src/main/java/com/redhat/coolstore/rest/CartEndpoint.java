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
 * Cart endpoints.
 *
 * <p>The routes are deliberately identical to the monolith, including the two-segment overlap:
 * {@code POST /cart/checkout/{cartId}} is matched before {@code POST /cart/{cartId}/{tmpId}} because
 * JAX-RS ranks literal segments above templates; {@code POST|DELETE /cart/{cartId}/{itemId}/{quantity}}
 * is a three-segment template route and cannot collide with either. The SPA
 * ({@code app/services/cart.js}) depends on these URLs, so they must not be "cleaned up".
 *
 * <p>The bean is {@code @ApplicationScoped} and stateless: cart state lives in the cart store keyed by
 * {@code cartId}, replacing the {@code @SessionScoped} resource plus {@code @Stateful} EJB pair.
 */
@ApplicationScoped
@Path("/cart")
public class CartEndpoint {

    @Inject
    ShoppingCartService shoppingCartService;

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

    @POST
    @Path("/{cartId}/{tmpId}")
    @Produces(MediaType.APPLICATION_JSON)
    public ShoppingCart set(@PathParam("cartId") String cartId,
                            @PathParam("tmpId") String tmpId) {
        return shoppingCartService.setCart(cartId, tmpId);
    }

    @DELETE
    @Path("/{cartId}/{itemId}/{quantity}")
    @Produces(MediaType.APPLICATION_JSON)
    public ShoppingCart delete(@PathParam("cartId") String cartId,
                               @PathParam("itemId") String itemId,
                               @PathParam("quantity") int quantity) {
        return shoppingCartService.deleteItem(cartId, itemId, quantity);
    }
}
