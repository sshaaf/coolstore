package com.redhat.coolstore.rest;

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.service.OrderService;
import com.redhat.coolstore.service.ProductService;
import com.redhat.coolstore.service.ShoppingCartService;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resource methods must stay thin delegates; the contract (paths/verbs) is asserted by
 * the integration suite. This keeps the legacy behaviour pinned without a container.
 */
class EndpointsTest {

    @Test
    void cartEndpointDelegatesAllFiveOperations() {
        ShoppingCartService service = mock(ShoppingCartService.class);
        ShoppingCart cart = new ShoppingCart();
        when(service.getShoppingCart("c1")).thenReturn(cart);
        when(service.checkOutShoppingCart("c1")).thenReturn(cart);
        when(service.addItem("c1", "329299", 2)).thenReturn(cart);
        when(service.copyCart("c1", "tmp")).thenReturn(cart);
        when(service.removeItem("c1", "329299", 1)).thenReturn(cart);
        CartEndpoint endpoint = new CartEndpoint(service);

        assertThat(endpoint.getCart("c1")).isSameAs(cart);
        assertThat(endpoint.checkout("c1")).isSameAs(cart);
        assertThat(endpoint.add("c1", "329299", 2)).isSameAs(cart);
        assertThat(endpoint.set("c1", "tmp")).isSameAs(cart);
        assertThat(endpoint.delete("c1", "329299", 1)).isSameAs(cart);
        verify(service).addItem("c1", "329299", 2);
    }

    @Test
    void productEndpointDelegates() {
        ProductService service = mock(ProductService.class);
        Product product = new Product();
        product.setItemId("329299");
        when(service.getProducts()).thenReturn(List.of(product));
        when(service.getProductByItemId("329299")).thenReturn(product);
        ProductEndpoint endpoint = new ProductEndpoint(service);

        assertThat(endpoint.listAll()).containsExactly(product);
        assertThat(endpoint.getProduct("329299")).isSameAs(product);
    }

    @Test
    void orderEndpointDelegates() {
        OrderService service = mock(OrderService.class);
        Order order = new Order();
        when(service.getOrders()).thenReturn(List.of(order));
        when(service.getOrderById(1L)).thenReturn(order);
        OrderEndpoint endpoint = new OrderEndpoint(service);

        assertThat(endpoint.listAll()).containsExactly(order);
        assertThat(endpoint.getOrder(1L)).isSameAs(order);
    }

    @Test
    void genericExceptionMapperAlwaysReturns500() {
        Response response = new ErrorMappers.GenericExceptionMapper()
                .toResponse(new IllegalStateException("boom"));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getEntity()).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) response.getEntity();
        assertThat(body).containsEntry("status", 500).containsEntry("error", "IllegalStateException");
    }

    @Test
    void webApplicationExceptionKeepsItsStatus() {
        Response response = new ErrorMappers.WebApplicationExceptionMapper()
                .toResponse(new WebApplicationException("not found", 404));

        assertThat(response.getStatus()).isEqualTo(404);
    }
}
