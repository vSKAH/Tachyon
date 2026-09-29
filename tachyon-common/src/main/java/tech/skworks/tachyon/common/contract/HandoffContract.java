package tech.skworks.tachyon.common.contract;

import io.grpc.MethodDescriptor;
import org.bson.RawBsonDocument;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 21/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public final class HandoffContract extends AbstractContractCreator {

    public static final String SERVICE_NAME = "tech.skworks.tachyon.contract.HandoffService";

    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> HANDOFF_STREAM_METHOD =
            createBidirectionalStreamingMethod(SERVICE_NAME, "HandoffStream", BSON_MARSHALLER);

    public static final String FIELD_ACTION = "action";
    public static final String FIELD_SERVER_ID = "server_id";
    public static final String FIELD_UUID = "uuid";

    public static final String FIELD_PAYLOAD = "payload";

    public static final String FIELD_SUCCESS = "success";
    public static final String FIELD_ERROR = "error";

    public static final String ACTION_REGISTER = "REGISTER";
    public static final String ACTION_REGISTER_ACK = "REGISTER_ACK";
    public static final String ACTION_REGISTER_OK = "REGISTER_OK";

    public static final String ACTION_EVICT = "EVICT";
    public static final String ACTION_EVICT_ACK = "EVICT_ACK";

    public static final String ACTION_PING = "PING";
    public static final String ACTION_PONG = "PONG";

    private HandoffContract() {}
}
