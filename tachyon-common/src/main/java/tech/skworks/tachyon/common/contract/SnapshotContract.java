package tech.skworks.tachyon.common.contract;

import io.grpc.MethodDescriptor;
import org.bson.RawBsonDocument;
import tech.skworks.tachyon.common.contract.util.ContractCreator;
import tech.skworks.tachyon.common.marshaller.BsonMarshaller;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 25/08/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class SnapshotContract extends ContractCreator {

    public static final String SERVICE_NAME = "tech.skworks.tachyon.contract.SnapshotService";

    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> TAKE_DATABASE_SNAPSHOT = createMethod(SERVICE_NAME, "TakeDatabaseSnapshot");
    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> TAKE_COMPONENT_SNAPSHOT = createMethod(SERVICE_NAME,"TakeComponentSnapshot");
    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> TOGGLE_SNAPSHOT_LOCK = createMethod(SERVICE_NAME,"ToggleSnapshotLock");
    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> LIST_SNAPSHOT = createMethod(SERVICE_NAME,"ListSnapshot");
    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> DECODE_SNAPSHOT = createMethod(SERVICE_NAME,"DecodeSnapshot");

    private SnapshotContract() {
        throw new RuntimeException("Unable to instantiate SnapshotContract");
    }

}
