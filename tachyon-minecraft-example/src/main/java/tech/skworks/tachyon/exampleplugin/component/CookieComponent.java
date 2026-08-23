package tech.skworks.tachyon.exampleplugin.component;

import lombok.Builder;
import tech.skworks.tachyon.api.component.ComponentCodec;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.libs.org.bson.BsonDocument;
import tech.skworks.tachyon.libs.org.bson.BsonInt64;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 23/08/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@Builder(toBuilder = true)
public record CookieComponent(long cookiesAmount) {

    public static class CookieComponentCodec implements ComponentCodec<CookieComponent> {
        @Override
        public ComponentNamespace getComponentNamespace() {
            return ComponentNamespace.of("TachyonCookies", "cookies");
        }

        @Override
        public Class<CookieComponent> getComponentClass() {
            return CookieComponent.class;
        }

        @Override
        public BsonDocument encode(CookieComponent component) {
            BsonDocument bsonDocument = new BsonDocument();
            bsonDocument.put("cookies", new BsonInt64(component.cookiesAmount));
            return bsonDocument;
        }

        @Override
        public CookieComponent decode(BsonDocument bson) {
            return new CookieComponent(bson.getInt64("cookies", new BsonInt64(1L)).getValue());
        }
    }

}
