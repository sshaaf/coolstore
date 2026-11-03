package com.redhat.coolstore.service;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.utils.Transformers;

@ApplicationScoped
public class ProductService {

    @Inject
    CatalogService cm;

    public ProductService() {
    }

    public List<Product> getProducts() {
        return cm.getCatalogItems().stream().map(Transformers::toProduct).collect(Collectors.toList());
    }

    public Product getProductByItemId(String itemId) {
        CatalogItemEntity entity = cm.getCatalogItemById(itemId);
        if (entity == null) {
            return null;
        }
        return Transformers.toProduct(entity);
    }
}
