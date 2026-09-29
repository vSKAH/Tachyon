package tech.skworks.tachyon.service.infra;

import io.grpc.*;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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

@ApplicationScoped
public class AuthenticationInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> AUTH_HEADER = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String BEARER_KEY = "Bearer ";
    private static final int BEARER_LEN = BEARER_KEY.length();


    @Inject
    Logger logger;

    @ConfigProperty(name = "tachyon.grpc.auth.enabled", defaultValue = "true")
    String authEnabledString;

    @ConfigProperty(name = "tachyon.grpc.auth.token", defaultValue = "CHANGE-ME")
    String authToken;

    private volatile boolean authEnabled;

    @PostConstruct
    public void onInit() {
        this.authEnabled = Boolean.parseBoolean(authEnabledString);
    }


    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                 Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        if (!authEnabled) return next.startCall(call, headers);
        final var authHeader = headers.get(AUTH_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_KEY)) {
            logger.warnf("[AuthenticationInterceptor] Rejected unauthenticated gRPC call to %s", call.getMethodDescriptor().getFullMethodName());
            call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid Authorization header"), headers);
            return new ServerCall.Listener<>() {};

        }

        final String token = authHeader.substring(BEARER_LEN).trim();
        if (!authToken.equals(token)) {
            logger.warnf("[AuthenticationInterceptor] Invalid token for gRPC call to %s", call.getMethodDescriptor().getFullMethodName());
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid authentication token"), headers);
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
    }
}
