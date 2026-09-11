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
 * @author Jimmy (vSKAH) - 25/08/2026
 * @version 1.0
 * @since 2.0.0-SNAPSHOT
 */
public class HealthContract extends AbstractContractCreator {

    public static final String SERVICE_NAME = "tech.skworks.tachyon.common.contract.HealthService";

    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> PING_METHOD = createUnaryMethod(SERVICE_NAME,"Ping", BSON_MARSHALLER);

    private HealthContract() {}
}
