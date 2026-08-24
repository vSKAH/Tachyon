package tech.skworks.tachyon.plugin.core.snapshots;

import tech.skworks.tachyon.libs.io.grpc.MethodDescriptor;
import tech.skworks.tachyon.libs.org.bson.RawBsonDocument;
import tech.skworks.tachyon.plugin.core.grpc.marshaller.BsonMarshaller;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 24/08/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class SnapshotContract {

    private static final String SERVICE_NAME = "tech.skworks.tachyon.SnapshotGrpcService";


    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> TAKE_DATABASE_SNAPSHOT =
            MethodDescriptor.<RawBsonDocument, RawBsonDocument>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "TakeDatabaseSnapshot"))
                    .setRequestMarshaller(BsonMarshaller.INSTANCE)
                    .setResponseMarshaller(BsonMarshaller.INSTANCE)
                    .build();

    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> TAKE_COMPONENT_SNAPSHOT =
            MethodDescriptor.<RawBsonDocument, RawBsonDocument>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "TakeComponentSnapshot"))
                    .setRequestMarshaller(BsonMarshaller.INSTANCE)
                    .setResponseMarshaller(BsonMarshaller.INSTANCE)
                    .build();

    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> TOGGLE_SNAPSHOT_LOCK =
            MethodDescriptor.<RawBsonDocument, RawBsonDocument>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "ToggleSnapshotLock"))
                    .setRequestMarshaller(BsonMarshaller.INSTANCE)
                    .setResponseMarshaller(BsonMarshaller.INSTANCE)
                    .build();

    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> LIST_SNAPSHOT =
            MethodDescriptor.<RawBsonDocument, RawBsonDocument>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "ListSnapshot"))
                    .setRequestMarshaller(BsonMarshaller.INSTANCE)
                    .setResponseMarshaller(BsonMarshaller.INSTANCE)
                    .build();

    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> DECODE_SNAPSHOT =
            MethodDescriptor.<RawBsonDocument, RawBsonDocument>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "DecodeSnapshot"))
                    .setRequestMarshaller(BsonMarshaller.INSTANCE)
                    .setResponseMarshaller(BsonMarshaller.INSTANCE)
                    .build();


}
