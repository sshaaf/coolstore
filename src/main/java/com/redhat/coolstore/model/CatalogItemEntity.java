package com.redhat.coolstore.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Catalogue entry. The primary key is the SKU itself, and inventory is joined 1:1 on the same key.
 *
 * <p>Mapping notes (Hibernate ORM 6):
 * <ul>
 *   <li>{@code description} is a PostgreSQL {@code text} column - {@code columnDefinition} is kept so that
 *       schema validation keeps working if Flyway is ever disabled.</li>
 *   <li>The 1:1 association stays EAGER: the legacy REST payload always contained inventory data.</li>
 * </ul>
 */
@Entity
@Table(name = "PRODUCT_CATALOG", uniqueConstraints = @UniqueConstraint(columnNames = "itemId"))
public class CatalogItemEntity {

    @Id
    private String itemId;

    @Column(length = 80)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String desc;

    @Column
    private double price;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @PrimaryKeyJoinColumn
    private InventoryEntity inventory;

    public CatalogItemEntity() {
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public InventoryEntity getInventory() {
        return inventory;
    }

    public void setInventory(InventoryEntity inventory) {
        this.inventory = inventory;
    }

    @Override
    public String toString() {
        return "CatalogItemEntity [itemId=" + itemId + ", name=" + name + ", desc=" + desc + ", price=" + price + "]";
    }
}
