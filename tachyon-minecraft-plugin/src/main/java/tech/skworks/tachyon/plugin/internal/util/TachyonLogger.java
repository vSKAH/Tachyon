package tech.skworks.tachyon.plugin.internal.util;


import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;

/**
 * Project Tachyon
 * Class TachyonLogger
 *
 * @author  Jimmy (vSKAH) - 09/04/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public class TachyonLogger {

    private final Logger log;
    private final String prefix;

    /**
     * @param pluginName Le nom principal du plugin (ex: "Tachyon")
     * @param moduleName Le nom du module (ex: "AuditManager")
     */
    public TachyonLogger(String pluginName, String moduleName) {
        this.log = LogManager.getLogger(pluginName);
        this.prefix = "[" + moduleName + "] ";
    }


    public void info(String message, Object... params) {
        log.info(new ParameterizedMessage(prefix + message, params));
    }

    public void warn(String message, Object... params) {
        log.warn(new ParameterizedMessage(prefix + message, params));
    }

    public void error(String message, Object... params) {
        log.error(new ParameterizedMessage(prefix + message, params));
    }

    public void error(Throwable exception, String message, Object... params) {
        log.error(new ParameterizedMessage(prefix + message, params), exception);
    }


    public void log(Level level, String message, Object... params) {
        log.log(level, new ParameterizedMessage(prefix + message, params));
    }

    public void log(Level level, Throwable exception, String message, Object... params) {
        log.log(level, new ParameterizedMessage(prefix + message, params), exception);
    }
}
