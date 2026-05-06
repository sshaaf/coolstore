package com.redhat.coolstore.service;

import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests for ShoppingCartService.
 * Tests business logic for shopping cart operations including pricing and promotions.
 */
@QuarkusTest
public class ShoppingCartServiceTest {

    @Inject
    ShoppingCartService shoppingCartService;

    private ShoppingCart testCart;

    @BeforeEach
    public void setup() {
        testCart = new ShoppingCart();
    }

    @Test
    public void testGetShoppingCart() {
        ShoppingCart cart = shoppingCartService.getShoppingCart("test-cart");

        assertThat(cart).isNotNull();
    }

    @Test
    public void testGetProduct() {
        Product product = shoppingCartService.getProduct("329299");

        assertThat(product).isNotNull();
        assertThat(product.getItemId()).isEqualTo("329299");
        assertThat(product.getName()).isEqualTo("Quarkus T-shirt");
    }

    @Test
    public void testGetProductWithInvalidId() {
        Product product = shoppingCartService.getProduct("invalid-id");

        assertThat(product).isNull();
    }

    @Test
    public void testPriceEmptyShoppingCart() {
        shoppingCartService.priceShoppingCart(testCart);

        assertThat(testCart.getCartItemTotal()).isEqualTo(0);
        assertThat(testCart.getShippingTotal()).isEqualTo(0);
        assertThat(testCart.getCartTotal()).isEqualTo(0);
    }

    @Test
    public void testPriceShoppingCartWithSingleItem() {
        Product product = shoppingCartService.getProduct("329299");
        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setQuantity(1);
        item.setPrice(product.getPrice());
        testCart.addShoppingCartItem(item);

        shoppingCartService.priceShoppingCart(testCart);

        assertThat(testCart.getCartItemTotal()).isGreaterThan(0);
        assertThat(testCart.getShippingTotal()).isGreaterThanOrEqualTo(0);
        assertThat(testCart.getCartTotal()).isGreaterThan(0);
    }

    @Test
    public void testPriceShoppingCartWithMultipleItems() {
        Product product1 = shoppingCartService.getProduct("329299");
        ShoppingCartItem item1 = new ShoppingCartItem();
        item1.setProduct(product1);
        item1.setQuantity(2);
        item1.setPrice(product1.getPrice());
        testCart.addShoppingCartItem(item1);

        Product product2 = shoppingCartService.getProduct("329199");
        ShoppingCartItem item2 = new ShoppingCartItem();
        item2.setProduct(product2);
        item2.setQuantity(1);
        item2.setPrice(product2.getPrice());
        testCart.addShoppingCartItem(item2);

        shoppingCartService.priceShoppingCart(testCart);

        assertThat(testCart.getShoppingCartItemList()).hasSize(2);
        assertThat(testCart.getCartItemTotal()).isGreaterThan(0);
    }

    @Test
    public void testPriceShoppingCartAppliesPromotions() {
        // Item 329299 has a 25% promotion
        Product product = shoppingCartService.getProduct("329299");
        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setQuantity(1);
        item.setPrice(product.getPrice());
        testCart.addShoppingCartItem(item);

        shoppingCartService.priceShoppingCart(testCart);

        assertThat(testCart.getCartItemPromoSavings()).isLessThan(0);
        assertThat(testCart.getShoppingCartItemList().get(0).getPromoSavings())
            .isLessThan(0);
    }

    @Test
    public void testPriceShoppingCartCalculatesShipping() {
        Product product = shoppingCartService.getProduct("329299");
        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setQuantity(1);
        item.setPrice(product.getPrice());
        testCart.addShoppingCartItem(item);

        shoppingCartService.priceShoppingCart(testCart);

        // For cart total < 25, shipping should be 2.99
        assertThat(testCart.getShippingTotal()).isGreaterThan(0);
    }

    @Test
    public void testPriceShoppingCartWithHighValueAppliesShippingInsurance() {
        // Add enough items to exceed $25 threshold
        Product product = shoppingCartService.getProduct("329299");
        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setQuantity(5);
        item.setPrice(product.getPrice());
        testCart.addShoppingCartItem(item);

        shoppingCartService.priceShoppingCart(testCart);

        // Should include shipping insurance for cart >= $25
        assertThat(testCart.getCartItemTotal()).isGreaterThanOrEqualTo(25);
        assertThat(testCart.getShippingTotal()).isGreaterThan(0);
    }

    @Test
    public void testPriceShoppingCartWithFreeShippingThreshold() {
        // Add enough items to exceed $75 for free shipping
        Product product = shoppingCartService.getProduct("329299");
        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setQuantity(15);
        item.setPrice(product.getPrice());
        testCart.addShoppingCartItem(item);

        shoppingCartService.priceShoppingCart(testCart);

        // Should have free shipping promotion for cart >= $75
        if (testCart.getCartItemTotal() >= 75) {
            assertThat(testCart.getShippingTotal()).isEqualTo(0);
            assertThat(testCart.getShippingPromoSavings()).isLessThan(0);
        }
    }

    @Test
    public void testPriceShoppingCartUpdatesProductPrices() {
        Product product = shoppingCartService.getProduct("329299");
        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setQuantity(1);
        item.setPrice(0); // Set wrong price initially
        testCart.addShoppingCartItem(item);

        shoppingCartService.priceShoppingCart(testCart);

        // Price should be updated from catalog
        assertThat(testCart.getShoppingCartItemList().get(0).getPrice())
            .isGreaterThan(0);
    }

    @Test
    public void testPriceNullShoppingCart() {
        // Should handle null gracefully
        shoppingCartService.priceShoppingCart(null);
        // No exception should be thrown
    }

    @Test
    public void testCheckOutShoppingCart() {
        String cartId = "checkout-test-cart";
        ShoppingCart cart = shoppingCartService.getShoppingCart(cartId);

        // Add items to cart
        Product product = shoppingCartService.getProduct("329299");
        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setQuantity(1);
        item.setPrice(product.getPrice());
        cart.addShoppingCartItem(item);

        ShoppingCart result = shoppingCartService.checkOutShoppingCart(cartId);

        // After checkout, cart should be empty and repriced
        assertThat(result.getShoppingCartItemList()).isEmpty();
        assertThat(result.getCartTotal()).isEqualTo(0);
    }
}
