package tech.skworks.tachyon.core.grpc;

import io.grpc.*;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 29/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public class ClientAuthentication implements ClientInterceptor {

    private static final Metadata.Key<String> AUTH_HEADER = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private final String token;


    public ClientAuthentication(@NotNull final String token) {
        this.token = Objects.requireNonNull(token, "token cannot be null");
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions,
                                                               Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(AUTH_HEADER, "Bearer " + token);
                super.start(responseListener, headers);
            }
        };
    }
}
