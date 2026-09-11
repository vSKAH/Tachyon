package tech.skworks.tachyon.common;

import org.bson.BsonBinaryWriter;
import org.bson.BsonDocument;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;

import java.nio.ByteBuffer;

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
public class BsonUtil {

    public static RawBsonDocument toRawBsonDocument(BsonDocument document) {

        if (document instanceof RawBsonDocument raw) {
            return raw;
        }

        BasicOutputBuffer buffer = new BasicOutputBuffer();
        try (BsonBinaryWriter writer = new BsonBinaryWriter(buffer)) {
            new BsonDocumentCodec().encode(writer, document, EncoderContext.builder().build());
        }
        return new RawBsonDocument(buffer.toByteArray());
    }

    public static byte[] toByteArray(RawBsonDocument doc) {
        ByteBuffer nio = doc.getByteBuffer().asNIO().duplicate();
        byte[] bytes = new byte[nio.remaining()];
        nio.get(bytes);
        return bytes;
    }

    public static byte[] toByteArray(BsonDocument document) {
        BasicOutputBuffer buffer = new BasicOutputBuffer();
        try (BsonBinaryWriter writer = new BsonBinaryWriter(buffer)) {
            new BsonDocumentCodec().encode(writer, document, EncoderContext.builder().build());
        }
        return buffer.toByteArray();
    }


}
