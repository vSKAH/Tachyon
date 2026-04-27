package tech.skworks.tachyon.service.infra;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.util.JsonFormat;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.constraint.NotNull;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Project Tachyon
 * Class DynamicProtobufRegistry
 *
 * @author  Jimmy (vSKAH) - 06/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
@ApplicationScoped
public class DynamicProtobufRegistry {

    @Inject
    Logger log;

    private volatile JsonFormat.Printer currentPrinter = JsonFormat.printer().omittingInsignificantWhitespace();
    private volatile JsonFormat.TypeRegistry currentTypeRegistry = JsonFormat.TypeRegistry.getEmptyTypeRegistry();
    private volatile Map<String, Descriptors.Descriptor> descriptorsByFullName = new HashMap<>();

    private final File protosDir = new File("config/protos/");
    private Map<String, Long> fileStates = new HashMap<>();

    @PostConstruct
    void init() {
        log.info("Initializing Dynamic Protobuf Registry...");
        if (!protosDir.exists()) {
            boolean created = protosDir.mkdirs();
            if (created) log.info("Created directory: " + protosDir.getAbsolutePath());
            else log.warn("Failed to create directory: " + protosDir.getAbsolutePath());
        }
        reloadIfModified();
    }

    @Scheduled(every = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reloadIfModified() {
        log.trace("Scanning for Protobuf descriptor changes...");

        if (!protosDir.exists() || !protosDir.isDirectory()) {
            log.warn("Protobuf directory missing: " + protosDir.getAbsolutePath());
            return;
        }

        File[] files = protosDir.listFiles((dir, name) -> name.endsWith(".desc"));
        if (files == null) {
            log.error("Failed to read directory: " + protosDir.getAbsolutePath());
            return;
        }

        boolean changed = false;
        Map<String, Long> currentStates = new HashMap<>();

        for (File f : files) {
            currentStates.put(f.getName(), f.lastModified());
            Long prev = fileStates.get(f.getName());
            if (prev == null || prev < f.lastModified()) {
                log.info((prev == null ? "New" : "Modified") + " descriptor: " + f.getName());
                changed = true;
            }
        }

        if (fileStates.size() != currentStates.size()) changed = true;

        if (changed) {
            log.info("Descriptor changes confirmed. Hot-reloading...");
            loadAllDescriptors(files);
            fileStates = currentStates;
        } else {
            log.trace("No changes detected.");
        }
    }

    private void loadAllDescriptors(File[] files) {
        log.debug("Loading " + files.length + " descriptor file(s)...");
        try {
            Map<String, DescriptorProtos.FileDescriptorProto> rawProtos = new HashMap<>();

            for (File f : files) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    DescriptorProtos.FileDescriptorSet set = DescriptorProtos.FileDescriptorSet.parseFrom(fis);
                    for (DescriptorProtos.FileDescriptorProto fdp : set.getFileList()) {
                        rawProtos.putIfAbsent(fdp.getName(), fdp);
                    }
                } catch (Exception e) {
                    log.error("Failed to parse: " + f.getName(), e);
                    throw e;
                }
            }

            Map<String, Descriptors.FileDescriptor> resolvedDescriptors = new HashMap<>();
            JsonFormat.TypeRegistry.Builder registryBuilder = JsonFormat.TypeRegistry.newBuilder();

            Map<String, Descriptors.Descriptor> newDescriptorMap = new HashMap<>();
            int messageCount = 0;

            for (String protoName : rawProtos.keySet()) {
                Descriptors.FileDescriptor fd = buildDescriptor(protoName, rawProtos, resolvedDescriptors);
                for (Descriptors.Descriptor msgDesc : fd.getMessageTypes()) {
                    registryBuilder.add(msgDesc);
                    newDescriptorMap.put(msgDesc.getFullName(), msgDesc);
                    messageCount++;
                    log.trace("Registered: " + msgDesc.getFullName());
                }
            }

            JsonFormat.TypeRegistry newTypeRegistry = registryBuilder.build();

            this.currentTypeRegistry = newTypeRegistry;
            this.currentPrinter = JsonFormat.printer().usingTypeRegistry(newTypeRegistry).omittingInsignificantWhitespace();
            this.descriptorsByFullName = newDescriptorMap;

            log.info(String.format("Hot-reload successful! %d files, %d message types.", files.length, messageCount));

        } catch (Exception e) {
            log.error("CRITICAL: Hot-reload failed! Previous state preserved.", e);
        }
    }

    private Descriptors.FileDescriptor buildDescriptor(String name, Map<String, DescriptorProtos.FileDescriptorProto> rawProtos, Map<String, Descriptors.FileDescriptor> resolved) throws Exception {

        if (resolved.containsKey(name)) return resolved.get(name);

        DescriptorProtos.FileDescriptorProto fdp = rawProtos.get(name);
        if (fdp == null) throw new IllegalStateException("Missing dependency: '" + name + "'");

        Descriptors.FileDescriptor[] deps = new Descriptors.FileDescriptor[fdp.getDependencyCount()];
        for (int i = 0; i < fdp.getDependencyCount(); i++) {
            deps[i] = buildDescriptor(fdp.getDependency(i), rawProtos, resolved);
        }

        Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(fdp, deps);
        resolved.put(name, fd);
        return fd;
    }


    public JsonFormat.Printer getPrinter() {
        return this.currentPrinter;
    }

    public JsonFormat.TypeRegistry getTypeRegistry() {
        return this.currentTypeRegistry;
    }

    @Nullable
    public Descriptors.Descriptor findDescriptor(String protoFullName) {
        return this.descriptorsByFullName.get(stripTypeURL(protoFullName));
    }

    public Collection<Descriptors.Descriptor> getLoadedDescriptors() {
        return descriptorsByFullName.values();
    }

    public static String stripTypeURL(@NotNull final String typeURL) {
        if (!typeURL.startsWith("type.googleapis.com/")) return typeURL;
        return typeURL.replace("type.googleapis.com/", "");
    }

    public static String rebuildTypeUrl(@NotNull final String typeURL) {
        if (typeURL.startsWith("type.googleapis.com/")) return typeURL;
        return "type.googleapis.com/" + typeURL;
    }
}
