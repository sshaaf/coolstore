package com.redhat.coolstore.service;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.utils.Transformers;

/**
 * Catalogue facade used by the REST layer and by cart pricing.
 */
@ApplicationScoped
public class ProductService {

    @Inject
    CatalogService catalogService;

    public ProductService() {
    }

    public ProductService(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public List<Product> getProducts() {
        return catalogService.getCatalogItems().stream()
                .map(Transformers::toProduct)
                .collect(Collectors.toList());
    }

    public Product getProductByItemId(String itemId) {
        CatalogItemEntity entity = catalogService.getCatalogItemById(itemId);
        if (entity == null) {
            return null;
        }
        return Transformers.toProduct(entity);
    }
}
