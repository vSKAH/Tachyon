package tech.skworks.tachyon.common.contract;

import io.grpc.MethodDescriptor;
import org.bson.RawBsonDocument;
import tech.skworks.tachyon.common.marshaller.BsonMarshaller;
import tech.skworks.tachyon.common.marshaller.EmptyMarshaller;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project community-compass</i></p>
 *
 * @author Jimmy (vSKAH) - 05/09/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public abstract class AbstractContractCreator {

    protected static final BsonMarshaller BSON_MARSHALLER = BsonMarshaller.INSTANCE;
    protected static final EmptyMarshaller EMPTY_MARSHALLER = EmptyMarshaller.INSTANCE;


    protected static <RESPONSE_TYPE> MethodDescriptor<RawBsonDocument, RESPONSE_TYPE> createUnaryMethod(final String serviceName, final String methodName, MethodDescriptor.Marshaller<RESPONSE_TYPE> responseMarshaller) {
        return MethodDescriptor.<RawBsonDocument, RESPONSE_TYPE>newBuilder()
                .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, methodName))
                .setType(MethodDescriptor.MethodType.UNARY)
                .setRequestMarshaller(BSON_MARSHALLER)
                .setResponseMarshaller(responseMarshaller).build();
    }

    protected static <RESPONSE_TYPE> MethodDescriptor<RawBsonDocument, RESPONSE_TYPE> createBidirectionalStreamingMethod(final String serviceName, final String methodName, MethodDescriptor.Marshaller<RESPONSE_TYPE> responseMarshaller) {
        return MethodDescriptor.<RawBsonDocument, RESPONSE_TYPE>newBuilder()
                .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, methodName))
                .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                .setRequestMarshaller(BSON_MARSHALLER)
                .setResponseMarshaller(responseMarshaller).build();
    }

    protected static <RESPONSE_TYPE> MethodDescriptor<RawBsonDocument, RESPONSE_TYPE> createClientStreamingMethod(final String serviceName, final String methodName, MethodDescriptor.Marshaller<RESPONSE_TYPE> responseMarshaller) {
        return MethodDescriptor.<RawBsonDocument, RESPONSE_TYPE>newBuilder()
                .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, methodName))
                .setType(MethodDescriptor.MethodType.CLIENT_STREAMING)
                .setRequestMarshaller(BSON_MARSHALLER)
                .setResponseMarshaller(responseMarshaller).build();
    }

}
