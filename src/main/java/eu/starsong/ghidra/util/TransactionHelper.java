package eu.starsong.ghidra.util;

import ghidra.program.model.listing.Program;
import ghidra.util.Msg;
import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Method;

public class TransactionHelper {
    
    @FunctionalInterface
    public interface GhidraSupplier<T> {
        T get() throws Exception;
    }

    public static <T> T executeInTransaction(Program program, String transactionName, GhidraSupplier<T> operation) 
        throws TransactionException {
        
        if (program == null) {
            throw new IllegalArgumentException("Program cannot be null for transaction");
        }

        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> exception = new AtomicReference<>();

        Runnable runner = () -> {
            int txId = -1;
            boolean success = false;
            try {
                txId = startTx(program, transactionName);
                if (txId < 0) {
                    throw new TransactionException("Failed to start transaction: " + transactionName);
                }
                result.set(operation.get());
                success = true;
            } catch (Throwable e) {
                // Capture any error to provide better diagnostics upstream
                exception.set(e instanceof Exception ? (Exception) e : new Exception(e));
                Msg.error(TransactionHelper.class, "Transaction failed: " + transactionName, e);
            } finally {
                if (txId >= 0) {
                    try {
                        endTx(program, txId, success);
                    } catch (Throwable endEx) {
                        Msg.error(TransactionHelper.class, "Failed to end transaction: " + transactionName, endEx);
                    }
                }
            }
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                // Already on EDT; run inline to avoid invokeAndWait reentrancy issues
                runner.run();
            } else {
                SwingUtilities.invokeAndWait(runner);
            }
        } catch (Exception e) {
            Throwable cause = e;
            // Unwrap InvocationTargetException to surface real cause
            if (e instanceof java.lang.reflect.InvocationTargetException && ((java.lang.reflect.InvocationTargetException) e).getTargetException() != null) {
                cause = ((java.lang.reflect.InvocationTargetException) e).getTargetException();
            }
            throw new TransactionException("Swing thread execution failed: " + cause.getClass().getSimpleName() + ": " + (cause.getMessage()), cause);
        }

        if (exception.get() != null) {
            Throwable cause = exception.get();
            throw new TransactionException("Operation failed: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), exception.get());
        }
        return result.get();
    }

    private static int startTx(Program program, String name) throws Exception {
        // Prefer direct call, but use reflection for cross-version safety
        try {
            return program.startTransaction(name);
        } catch (NoSuchMethodError nsme) {
            // Fallback via reflection (older/newer API variance)
            Method m = Program.class.getMethod("startTransaction", String.class);
            Object ret = m.invoke(program, name);
            return (Integer) ret;
        }
    }

    private static void endTx(Program program, int id, boolean success) throws Exception {
        // Try 2-arg version first
        try {
            program.endTransaction(id, success);
            return;
        } catch (NoSuchMethodError nsme) {
            // Try reflection variants
            try {
                Method m2 = Program.class.getMethod("endTransaction", int.class, boolean.class);
                m2.invoke(program, id, success);
                return;
            } catch (NoSuchMethodException e2) {
                // Try 3-arg variant if present in some versions
                try {
                    Method m3 = Program.class.getMethod("endTransaction", int.class, boolean.class, boolean.class);
                    // Assume third arg = false (do not commit if already handled) or true? Use success for commit, and true to notify listeners
                    m3.invoke(program, id, success, Boolean.TRUE);
                    return;
                } catch (NoSuchMethodException e3) {
                    // Re-throw the original
                    throw nsme;
                }
            }
    }
    }

    public static class TransactionException extends Exception {
        public TransactionException(String message) { super(message); }
        public TransactionException(String message, Throwable cause) { super(message, cause); }
    }
}
