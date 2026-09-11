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
public class PlayerSessionContract extends AbstractContractCreator {

    public static final String SERVICE_NAME = "tech.skworks.tachyon.contract.SessionService";

    public static final MethodDescriptor<RawBsonDocument, Empty> FREE_PLAYER_METHOD = createUnaryMethod(SERVICE_NAME, "FreePlayer", EMPTY_MARSHALLER);
    public static final MethodDescriptor<RawBsonDocument, Empty> HEARTBEAT_BATCH_METHOD = createUnaryMethod(SERVICE_NAME, "PlayerHeartBeatBatch", EMPTY_MARSHALLER);

    private PlayerSessionContract() {}

}
