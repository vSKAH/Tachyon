package tech.skworks.tachyon.exampleplugin.component;

import lombok.Builder;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import tech.skworks.tachyon.api.component.ComponentCodec;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.api.component.ComponentPreviewHandler;
import tech.skworks.tachyon.libs.org.bson.BsonDocument;
import tech.skworks.tachyon.libs.org.bson.BsonInt64;

@Builder(toBuilder = true)
public record CookieComponent(long cookiesAmount) {

    private static final String NAMESPACE_GROUP = "TachyonCookies";
    private static final String NAMESPACE_KEY = "cookies";
    private static final long DEFAULT_COOKIE_AMOUNT = 1L;

    public CookieComponent
    {
        if (cookiesAmount < 0) {
            throw new IllegalArgumentException("cookiesAmount cannot be negative: %d".formatted(cookiesAmount));
        }
    }

    public static class CookieComponentCodec implements ComponentCodec<CookieComponent>, ComponentPreviewHandler<ItemStack, CookieComponent> {

        private static final ComponentNamespace NAMESPACE = ComponentNamespace.of(NAMESPACE_GROUP, NAMESPACE_KEY);
        private static final ItemStack ICON_TEMPLATE = new ItemStack(Material.COOKIE);

        //to avoid issue between encoding and decoding it's recommended declare it one time.
        private static final String BSON_FIELD_COOKIES = "cookies";


        @Override
        public ComponentNamespace getComponentNamespace() {
            return NAMESPACE;
        }

        @Override
        public Class<CookieComponent> getComponentClass() {
            return CookieComponent.class;
        }

        @Override
        public BsonDocument encode(CookieComponent component) {
            return new BsonDocument(BSON_FIELD_COOKIES, new BsonInt64(component.cookiesAmount()));
        }

        @Override
        public CookieComponent decode(BsonDocument bson) {
            final var value = bson.getNumber(BSON_FIELD_COOKIES, new BsonInt64(DEFAULT_COOKIE_AMOUNT)).longValue();
            return new CookieComponent(value);
        }

        @Override
        public ItemStack buildComponentIcon() {
            return ICON_TEMPLATE.clone();
        }

        @Override
        public ItemStack[] buildComponentDataDisplay(CookieComponent record) {

            ItemStack itemStack = new ItemStack(Material.COOKIE);
            ItemMeta meta = itemStack.getItemMeta();
            meta.setDisplayName(" Amount of Cookie: " + record.cookiesAmount());
            itemStack.setItemMeta(meta);

            return new ItemStack[]{itemStack};
        }
    }

}
