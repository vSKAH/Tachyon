package tech.skworks.tachyon.api.component;


import org.bson.BsonDocument;

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
 * @since 2.0.0-SNAPSHOT
 */
public interface ComponentCodec<T extends Record> {

    ComponentNamespace getComponentNamespace();

    Class<T> getComponentClass();

    BsonDocument encode(T component);

    T decode(BsonDocument bson);
}
