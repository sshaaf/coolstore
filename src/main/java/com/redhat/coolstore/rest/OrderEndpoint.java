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

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.service.OrderService;

/**
 * Order read endpoints (the write path is asynchronous through the order event stream).
 */
@ApplicationScoped
@Path("/orders")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OrderEndpoint {

    @Inject
    OrderService orderService;

    @GET
    public List<Order> listAll() {
        return orderService.getOrders();
    }

    @GET
    @Path("/{orderId}")
    public Order getOrder(@PathParam("orderId") long orderId) {
        return orderService.getOrderById(orderId);
    }
}
