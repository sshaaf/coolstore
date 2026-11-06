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

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.service.OrderService;

@ApplicationScoped
@Path("/orders")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OrderEndpoint {

    private final OrderService orderService;

    @Inject
    public OrderEndpoint(OrderService orderService) {
        this.orderService = orderService;
    }

    @GET
    @Path("/")
    public List<Order> listAll() {
        return orderService.getOrders();
    }

    @GET
    @Path("/{orderId}")
    public Order getOrder(@PathParam("orderId") long orderId) {
        return orderService.getOrderById(orderId);
    }
}
