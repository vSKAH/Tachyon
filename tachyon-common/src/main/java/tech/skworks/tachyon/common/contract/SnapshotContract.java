package tech.skworks.tachyon.common.contract;

import io.grpc.MethodDescriptor;
import org.bson.RawBsonDocument;
import tech.skworks.tachyon.common.types.Empty;

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
public class SnapshotContract extends AbstractContractCreator {

    public static final String SERVICE_NAME = "tech.skworks.tachyon.contract.SnapshotService";

    public static final MethodDescriptor<RawBsonDocument, Empty> TAKE_DATABASE_SNAPSHOT = createUnaryMethod(SERVICE_NAME, "TakeDatabaseSnapshot", EMPTY_MARSHALLER);
    public static final MethodDescriptor<RawBsonDocument, Empty> TAKE_COMPONENT_SNAPSHOT = createUnaryMethod(SERVICE_NAME, "TakeComponentSnapshot", EMPTY_MARSHALLER);
    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> TOGGLE_SNAPSHOT_LOCK = createUnaryMethod(SERVICE_NAME, "ToggleSnapshotLock", BSON_MARSHALLER);
    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> LIST_SNAPSHOT_DAYS = createUnaryMethod(SERVICE_NAME, "ListSnapshotDays", BSON_MARSHALLER);
    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> GET_SNAPSHOTS_FOR_DAY = createUnaryMethod(SERVICE_NAME, "GetSnapshotsForDay", BSON_MARSHALLER);
    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> DECODE_SNAPSHOT = createUnaryMethod(SERVICE_NAME, "DecodeSnapshot", BSON_MARSHALLER);
    @Deprecated
    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> LIST_SNAPSHOT = createUnaryMethod(SERVICE_NAME, "ListSnapshot", BSON_MARSHALLER);

    private SnapshotContract() {}
}
