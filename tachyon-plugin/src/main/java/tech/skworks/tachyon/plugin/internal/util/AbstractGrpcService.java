package tech.skworks.tachyon.plugin.internal.util;

import tech.skworks.tachyon.libs.grpc.StatusRuntimeException;
import tech.skworks.tachyon.plugin.internal.grpc.GrpcClientManager;
import tech.skworks.tachyon.plugin.internal.metric.scraper.TachyonMetrics;

import javax.annotation.Nullable;

/**
 * Project Tachyon
 * Class AbstractGrpcService
 *
 * @author  Jimmy (vSKAH) - 13/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public abstract class AbstractGrpcService {

    protected final @Nullable TachyonMetrics tachyonMetrics;
    protected final GrpcClientManager grpcClientManager;

    public AbstractGrpcService(@Nullable TachyonMetrics tachyonMetrics, GrpcClientManager grpcClientManager) {
        this.tachyonMetrics = tachyonMetrics;
        this.grpcClientManager = grpcClientManager;
    }

    public AutoCloseable startTimer(String method) {
        return tachyonMetrics != null ? tachyonMetrics.startGrpcTimer(method) : () -> {};
    }

    public void recordError(String method, Exception e) {
        if (tachyonMetrics == null) return;
        String label = e instanceof StatusRuntimeException ex
                ? ex.getStatus().getCode().name()
                : e.getClass().getSimpleName();
        tachyonMetrics.recordGrpcError(method, label);
    }

    public void recordError(String method, String errorCode) {
        if (tachyonMetrics != null) tachyonMetrics.recordGrpcError(method, errorCode);
    }

}
