package com.redhat.coolstore.model;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * Persisted order. {@code orderId} is generated from the {@code hibernate_sequence} sequence that
 * {@code V1_1__CreateSchema.sql} creates - the column is a plain {@code int8} without an identity
 * definition, so {@link GenerationType#SEQUENCE} (allocationSize 1 to match the DDL increment) is the
 * faithful mapping. The stale {@code @Column(name="TOTAL_PRICE")} mapping was removed: no such column
 * exists in the schema.
 */
@Entity
@Table(name = "ORDERS")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "hibernate_sequence")
    @SequenceGenerator(name = "hibernate_sequence", sequenceName = "hibernate_sequence", allocationSize = 1)
    private long orderId;

    private String customerName;

    private String customerEmail;

    private double orderValue;

    private double retailPrice;

    private double discount;

    private double shippingFee;

    private double shippingDiscount;

    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "ORDER_ID")
    private List<OrderItem> itemList = new ArrayList<>();

    public Order() {
    }

    public long getOrderId() {
        return orderId;
    }

    public void setOrderId(long orderId) {
        this.orderId = orderId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail;
    }

    public double getOrderValue() {
        return orderValue;
    }

    public void setOrderValue(double orderValue) {
        this.orderValue = orderValue;
    }

    public double getRetailPrice() {
        return retailPrice;
    }

    public void setRetailPrice(double retailPrice) {
        this.retailPrice = retailPrice;
    }

    public double getDiscount() {
        return discount;
    }

    public void setDiscount(double discount) {
        this.discount = discount;
    }

    public double getShippingFee() {
        return shippingFee;
    }

    public void setShippingFee(double shippingFee) {
        this.shippingFee = shippingFee;
    }

    public double getShippingDiscount() {
        return shippingDiscount;
    }

    public void setShippingDiscount(double shippingDiscount) {
        this.shippingDiscount = shippingDiscount;
    }

    public void setItemList(List<OrderItem> itemList) {
        this.itemList = itemList;
    }

    public List<OrderItem> getItemList() {
        return itemList;
    }

    @Override
    public String toString() {
        return "Order [orderId=" + orderId
                + ", customerName=" + customerName
                + ", customerEmail=" + customerEmail
                + ", orderValue=" + orderValue
                + ", retailPrice=" + retailPrice
                + ", discount=" + discount
                + ", shippingFee=" + shippingFee
                + ", shippingDiscount=" + shippingDiscount
                + ", itemList=" + itemList
                + "]";
    }
}
