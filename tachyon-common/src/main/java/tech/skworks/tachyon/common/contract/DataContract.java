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
public class DataContract extends ContractCreator {

    public static final String SERVICE_NAME = "tech.skworks.tachyon.contract.DataService";

    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> PULL_PROFILE_METHOD = createMethod(SERVICE_NAME, "PullProfile");
    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> PUSH_PROFILE_METHOD = createMethod(SERVICE_NAME, "PushProfile");

    private DataContract() {
        throw new RuntimeException("Unable to instantiate PlayerDataContract");
    }



}
