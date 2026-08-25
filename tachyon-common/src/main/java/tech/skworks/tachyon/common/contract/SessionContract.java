package tech.skworks.tachyon.common.contract;


import io.grpc.MethodDescriptor;
import org.bson.RawBsonDocument;
import tech.skworks.tachyon.common.contract.util.ContractCreator;

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
public class SessionContract extends ContractCreator {

    public static final String SERVICE_NAME = "tech.skworks.tachyon.contract.SessionService";

    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> FREE_PLAYER_METHOD = createMethod(SERVICE_NAME, "FreePlayer");
    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> HEARTBEAT_BATCH_METHOD = createMethod(SERVICE_NAME, "PlayerHeartBeatBatch");

    private SessionContract() {
        throw new RuntimeException("Unable to instantiate PlayerSessionContract");
    }

}
