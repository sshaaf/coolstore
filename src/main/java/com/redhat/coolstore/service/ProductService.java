package com.redhat.coolstore.service;

import com.redhat.coolstore.model.CatalogItemEntity;
import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.utils.Transformers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.stream.Collectors;

import static com.redhat.coolstore.utils.Transformers.toProduct;

/** Was an EJB {@code @Stateless} bean; now CDI. Signatures and behaviour unchanged. */
@ApplicationScoped
public class ProductService {

    private final CatalogService catalogService;

    @Inject
    public ProductService(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public List<Product> getProducts() {
        return catalogService.getCatalogItems().stream().map(Transformers::toProduct).collect(Collectors.toList());
    }

    public Product getProductByItemId(String itemId) {
        CatalogItemEntity entity = catalogService.getCatalogItemById(itemId);
        if (entity == null) {
            return null;
        }
        return toProduct(entity);
    }
}
