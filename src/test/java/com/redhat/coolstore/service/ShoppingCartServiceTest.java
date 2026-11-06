package com.redhat.coolstore.service;

import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShoppingCartServiceTest {

    private ProductService productService;
    private ShoppingCartOrderProcessor orderProcessor;
    private InMemoryCartStore cartStore;
    private ShoppingCartService service;

    private static Product product(String id, double price) {
        Product p = new Product();
        p.setItemId(id);
        p.setName("name-" + id);
        p.setPrice(price);
        return p;
    }

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        when(productService.getProductByItemId("329299")).thenReturn(product("329299", 10.00));
        when(productService.getProductByItemId("444435")).thenReturn(product("444435", 13.00));
        when(productService.getProductByItemId("165614")).thenReturn(product("165614", 14.45));
        when(productService.getProductByItemId("unknown")).thenReturn(null);
        orderProcessor = mock(ShoppingCartOrderProcessor.class);
        cartStore = new InMemoryCartStore();
        service = new ShoppingCartService(productService, new PromoService(), new ShippingService(),
                orderProcessor, cartStore);
    }

    private static ShoppingCartItem line(Product product, int quantity) {
        ShoppingCartItem item = new ShoppingCartItem();
        item.setProduct(product);
        item.setPrice(product.getPrice());
        item.setQuantity(quantity);
        return item;
    }

    private static void assertMoney(double actual, double expected) {
        assertThat(actual).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void pricedCartWithPromotionMatchesBaseline() {
        ShoppingCart cart = new ShoppingCart();
        cart.addShoppingCartItem(line(product("329299", 10.00), 2));

        service.priceShoppingCart(cart);

        assertMoney(cart.getCartItemTotal(), 15.00);
        assertMoney(cart.getCartItemPromoSavings(), -5.00);
        assertMoney(cart.getShippingTotal(), 2.99);
        assertThat(cart.getShippingPromoSavings()).isZero();
        assertMoney(cart.getCartTotal(), 17.99);
    }

    @Test
    void pricedCartWithShippingInsuranceMatchesBaseline() {
        ShoppingCart cart = new ShoppingCart();
        cart.addShoppingCartItem(line(product("329299", 10.00), 2));
        cart.addShoppingCartItem(line(product("444435", 13.00), 3));

        service.priceShoppingCart(cart);

        assertMoney(cart.getCartItemTotal(), 54.00);
        assertMoney(cart.getShippingTotal(), 8.07); // 6.99 + 2% of 54
        assertMoney(cart.getCartTotal(), 62.07);
    }

    @Test
    void freeShippingAppliesAbove75() {
        ShoppingCart cart = new ShoppingCart();
        cart.addShoppingCartItem(line(product("444435", 13.00), 6));

        service.priceShoppingCart(cart);

        assertMoney(cart.getCartItemTotal(), 78.00);
        assertThat(cart.getShippingTotal()).isZero();
        assertMoney(cart.getShippingPromoSavings(), -10.55);
        assertMoney(cart.getCartTotal(), 78.00);
    }

    @Test
    void repricingIsIdempotent() {
        ShoppingCart cart = new ShoppingCart();
        cart.addShoppingCartItem(line(product("329299", 10.00), 2));

        service.priceShoppingCart(cart);
        service.priceShoppingCart(cart);

        assertMoney(cart.getCartItemTotal(), 15.00);
        assertMoney(cart.getCartItemPromoSavings(), -5.00);
        assertMoney(cart.getCartTotal(), 17.99);
    }

    @Test
    void addingSameItemTwiceIsDedupedIntoOneLine() {
        service.addItem("c1", "329299", 1);
        ShoppingCart cart = service.addItem("c1", "329299", 1);

        assertThat(cart.getShoppingCartItemList()).hasSize(1);
        assertThat(cart.getShoppingCartItemList().get(0).getQuantity()).isEqualTo(2);
        assertMoney(cart.getCartTotal(), 17.99);
        assertThat(cartStore.contains("c1")).isTrue();
    }

    @Test
    void addRepricesAndPersistsTheCart() {
        ShoppingCart cart = service.addItem("c1", "444435", 2);

        assertMoney(cart.getCartItemTotal(), 26.00);
        assertMoney(cart.getCartTotal(), 31.51); // 26 + 4.99 + 0.52 insurance
    }

    @Test
    void deleteReducesQuantityAndReprices() {
        service.addItem("c1", "329299", 2);
        ShoppingCart cart = service.removeItem("c1", "329299", 1);

        assertThat(cart.getShoppingCartItemList()).hasSize(1);
        assertThat(cart.getShoppingCartItemList().get(0).getQuantity()).isEqualTo(1);
        assertMoney(cart.getCartTotal(), 10.49);
    }

    @Test
    void deleteMoreThanAvailableRemovesTheLine() {
        service.addItem("c1", "329299", 2);
        ShoppingCart cart = service.removeItem("c1", "329299", 5);

        assertThat(cart.getShoppingCartItemList()).isEmpty();
        assertThat(cart.getCartTotal()).isZero();
    }

    @Test
    void checkoutPublishesThenReturnsEmptiedCart() {
        service.addItem("c1", "329299", 2);
        ShoppingCart priced = service.getShoppingCart("c1");

        ShoppingCart afterCheckout = service.checkOutShoppingCart("c1");

        verify(orderProcessor).process(any(ShoppingCart.class));
        assertThat(priced.getShoppingCartItemList()).hasSize(1);
        assertThat(priced.getShoppingCartItemList().get(0).getQuantity()).isEqualTo(2);
        assertThat(afterCheckout.getShoppingCartItemList()).isEmpty();
        assertThat(afterCheckout.getCartTotal()).isZero();
        assertThat(service.getShoppingCart("c1").getShoppingCartItemList()).isEmpty();
    }

    @Test
    void copyCartCopiesAndRepricesTheSourceItems() {
        service.addItem("tmp", "329299", 2);

        ShoppingCart copied = service.copyCart("c1", "tmp");

        assertThat(copied.getShoppingCartItemList()).hasSize(1);
        assertThat(copied.getShoppingCartItemList().get(0).getQuantity()).isEqualTo(2);
        assertMoney(copied.getCartTotal(), 17.99);
        verify(orderProcessor, never()).process(any());
    }

    @Test
    void missingCartIsAnEmptyCart() {
        ShoppingCart cart = service.getShoppingCart("does-not-exist");

        assertThat(cart).isNotNull();
        assertThat(cart.getShoppingCartItemList()).isEmpty();
        assertThat(cart.getCartTotal()).isZero();
    }

    @Test
    void unknownProductFailsLoudly() {
        assertThatThrownBy(() -> service.addItem("c1", "unknown", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void dedupeMergesQuantitiesForDuplicateProductIds() {
        List<ShoppingCartItem> merged = service.dedupeCartItems(List.of(
                line(product("329299", 10.00), 2),
                line(product("329299", 10.00), 3)));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).getQuantity()).isEqualTo(5);
    }

    @Test
    void priceShoppingCartIgnoresNull() {
        service.priceShoppingCart(null);
        verify(productService, never()).getProductByItemId(eq("329299"));
        verify(orderProcessor, never()).process(any());
    }

    @Test
    void getProductDelegatesToProductService() {
        assertThat(service.getProduct("329299").getName()).isEqualTo("name-329299");
        Mockito.verify(productService).getProductByItemId("329299");
    }
}
