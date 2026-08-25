package tech.skworks.tachyon.common.marshaller;

import io.grpc.*;
import org.bson.BsonBinaryWriter;
import org.bson.BsonDocument;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class BsonMarshaller implements MethodDescriptor.Marshaller<RawBsonDocument> {

    public static final BsonMarshaller INSTANCE = new BsonMarshaller();
    public static final RawBsonDocument EMPTY = toRawBsonDocument(new BsonDocument());

    @Override
    public InputStream stream(RawBsonDocument value) {
        ByteBuffer nio = value.getByteBuffer().asNIO().duplicate();

        if (nio.hasArray()) {
            return new BsonFastStream(nio.array(), nio.arrayOffset() + nio.position(), nio.remaining());
        }

        byte[] bytes = new byte[nio.remaining()];
        nio.get(bytes);
        return new BsonFastStream(bytes, 0, bytes.length);
    }

    @Override
    public RawBsonDocument parse(InputStream stream) {
        try {
            if (stream instanceof BsonFastStream fastStream) {
                return fastStream.toRawBsonDocument();
            }
            return new RawBsonDocument(stream.readAllBytes());
        } catch (Exception e) {
            throw new StatusRuntimeException(Status.INTERNAL.withCause(e).withDescription("Failed to parse BSON"));
        }
    }

    public static RawBsonDocument toRawBsonDocument(BsonDocument document) {
        BasicOutputBuffer buffer = new BasicOutputBuffer();
        try (BsonBinaryWriter writer = new BsonBinaryWriter(buffer)) {
            new BsonDocumentCodec().encode(writer, document, EncoderContext.builder().build());
        }
        return new RawBsonDocument(buffer.toByteArray());
    }

    private static final class BsonFastStream extends ByteArrayInputStream implements KnownLength, Drainable {

        public BsonFastStream(byte[] buf) {
            super(buf);
        }

        public RawBsonDocument toRawBsonDocument() {
            return new RawBsonDocument(this.buf, this.pos, this.count - this.pos);
        }

        public BsonFastStream(byte[] buf, int offset, int length) {
            super(buf, offset, length);
        }

        @Override
        public int drainTo(OutputStream target) throws IOException {
            int len = this.count - this.pos;
            if (len > 0) {
                target.write(this.buf, this.pos, len);
                this.pos = this.count;
            }
            return len;
        }

        @Override
        public int available() {
            return this.count - this.pos;
        }
    }

}
