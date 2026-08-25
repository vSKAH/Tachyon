package tech.skworks.tachyon.common.contract.util;

import io.grpc.MethodDescriptor;
import org.bson.RawBsonDocument;
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
 * @since 2.0.0-SNAPSHOT
 */
public abstract class ContractCreator {


    protected static MethodDescriptor<RawBsonDocument, RawBsonDocument> createMethod(final String serviceName, final String methodName) {
        return MethodDescriptor.<RawBsonDocument, RawBsonDocument>newBuilder()
                .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, methodName))
                .setType(MethodDescriptor.MethodType.UNARY)
                .setRequestMarshaller(BsonMarshaller.INSTANCE)
                .setResponseMarshaller(BsonMarshaller.INSTANCE).build();
    }

}
