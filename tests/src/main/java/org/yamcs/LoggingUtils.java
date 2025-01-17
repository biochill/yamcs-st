package org.yamcs;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class LoggingUtils {

    public static void configureLogging(Level level) {
        Logger logger = Logger.getLogger("org.yamcs");
        logger.setLevel(level);
        ConsoleHandler ch = null;
        
        for (Handler h: Logger.getLogger("").getHandlers()) {
            if(h instanceof ConsoleHandler) {
                ch = (ConsoleHandler) h;
                break;
            }
        }
        if(ch==null) {
            ch = new ConsoleHandler();
            Logger.getLogger("").addHandler(ch);
        }
        ch.setLevel(level);
    }

    /**
     * use to enable logging during junit tests debugging.
     */
    public static void enableTracing() {
        configureLogging(Level.ALL);
    }

    /**
     * Start capturing all the logs generated by the class into the ArrayLogHandler.
     * <p>
     * The capture should be stopped by calling {@link #stopCapture}
     */
    public static ArrayLogHandler startCapture(Class<?> clazz) {
        Logger logger = Logger.getLogger(clazz.getName());
        logger.setLevel(Level.ALL);

        ArrayLogHandler ah = new ArrayLogHandler();
        ah.setLevel(Level.ALL);

        logger.addHandler(ah);
        return ah;
    }

    public static void stopCapture(Class<?> clazz) {
        Logger logger = Logger.getLogger(clazz.getName());

        for (Handler h : logger.getHandlers()) {
            if (h instanceof ArrayLogHandler) {
                logger.removeHandler(h);
            }
        }
    }

    public static class ArrayLogHandler extends Handler {
        
        public final List<LogRecord> records = new ArrayList<>();
        
        
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() throws SecurityException {
        }

        public boolean contains(String msg) {
            for (LogRecord lr : records) {
                if (lr.getMessage().contains(msg)) {
                    return true;
                }
            }
            return false;
        }
    }
}
