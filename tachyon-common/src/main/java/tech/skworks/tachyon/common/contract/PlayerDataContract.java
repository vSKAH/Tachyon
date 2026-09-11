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
 * @since 2.0.0-SNAPSHOT
 */
public class PlayerDataContract extends AbstractContractCreator {

    public static final String SERVICE_NAME = "tech.skworks.tachyon.contract.DataService";

    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> PULL_PROFILE_METHOD = createUnaryMethod(SERVICE_NAME, "PullProfile", BSON_MARSHALLER);
    public static final MethodDescriptor<RawBsonDocument, Empty> PUSH_PROFILE_METHOD = createUnaryMethod(SERVICE_NAME, "PushProfile", EMPTY_MARSHALLER);
    public static final MethodDescriptor<RawBsonDocument, Empty> PUSH_DLQ_BATCH_METHOD = createUnaryMethod(SERVICE_NAME, "PushDLQBatch", EMPTY_MARSHALLER);


    public static final String FIELD_UUID = "uuid";
    public static final String FIELD_SAVE = "save";
    public static final String FIELD_VERSIONS = "versions";
    public static final String FIELD_REMOVE = "remove";
    public static final String FIELD_COMPONENTS = "components";

    private PlayerDataContract() {}
}
