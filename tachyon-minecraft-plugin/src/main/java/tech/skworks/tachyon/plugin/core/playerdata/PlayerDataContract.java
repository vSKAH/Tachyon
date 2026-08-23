package tech.skworks.tachyon.plugin.core.playerdata;

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
 * @author Jimmy (vSKAH) - 23/08/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class PlayerDataContract {


    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> PULL_PROFILE_METHOD =
            MethodDescriptor.<RawBsonDocument, RawBsonDocument>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName("tech.skworks.tachyon.PlayerDataService", "PullProfile"))
                    .setRequestMarshaller(BsonMarshaller.INSTANCE)
                    .setResponseMarshaller(BsonMarshaller.INSTANCE)
                    .build();

    public static final MethodDescriptor<RawBsonDocument, RawBsonDocument> PUSH_PROFILE_METHOD =
            MethodDescriptor.<RawBsonDocument, RawBsonDocument>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName("tech.skworks.tachyon.PlayerDataService", "PushProfile"))
                    .setRequestMarshaller(BsonMarshaller.INSTANCE)
                    .setResponseMarshaller(BsonMarshaller.INSTANCE)
                    .build();


}
