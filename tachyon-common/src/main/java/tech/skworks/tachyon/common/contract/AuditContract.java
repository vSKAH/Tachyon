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
public class AuditContract extends AbstractContractCreator {

    public static final String SERVICE_NAME = "tech.skworks.tachyon.common.contract.AuditService";

    public static final MethodDescriptor<RawBsonDocument, Empty> DIRECT_LOG_ENTRY_METHOD = createUnaryMethod(SERVICE_NAME, "DirectLogEntry", EMPTY_MARSHALLER);
    public static final MethodDescriptor<RawBsonDocument, Empty> LOG_ENTRY_BATCH_METHOD = createUnaryMethod(SERVICE_NAME, "LogEntryBatch", EMPTY_MARSHALLER);

    private AuditContract() {}
}
