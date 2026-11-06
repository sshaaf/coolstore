package com.redhat.coolstore.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * Order line. {@code ID} is generated from the shared {@code hibernate_sequence} sequence created by the
 * Flyway baseline (see {@link Order} for the rationale).
 */
@Entity
@Table(name = "ORDER_ITEMS")
public class OrderItem {

    @Id
    @Column(name = "ID")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "hibernate_sequence")
    @SequenceGenerator(name = "hibernate_sequence", sequenceName = "hibernate_sequence", allocationSize = 1)
    private long id;

    private int quantity;

    private String productId;

    public OrderItem() {
    }

    /**
     * Surrogate key. Not part of the published order JSON: the monolith's {@code OrderItem} only
     * exposed {@code productId} and {@code quantity}, and that shape is part of the API contract.
     */
    @JsonIgnore
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    @Override
    public String toString() {
        return "OrderItem [productId=" + productId + ", quantity=" + quantity + "]";
    }
}
