package tech.skworks.tachyon.common.marshaller;

import io.grpc.KnownLength;
import io.grpc.MethodDescriptor;
import tech.skworks.tachyon.common.types.Empty;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

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
public class EmptyMarshaller implements MethodDescriptor.Marshaller<Empty> {

    public static final EmptyMarshaller INSTANCE = new EmptyMarshaller();
    private static final byte[] EMPTY_BYTES = new byte[0];

    @Override
    public InputStream stream(Empty value) {
        return new EmptyStream();
    }

    @Override
    public Empty parse(InputStream stream) {
        return Empty.INSTANCE;
    }

    private static final class EmptyStream extends ByteArrayInputStream implements KnownLength {
        public EmptyStream() {
            super(EMPTY_BYTES);
        }
    }


}
