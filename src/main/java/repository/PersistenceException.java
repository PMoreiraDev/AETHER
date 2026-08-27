package repository;

/**
 * Exception thrown when application data cannot be persisted.
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class PersistenceException extends RuntimeException {

    /**
     * Creates a persistence exception.
     *
     * @param message error description
     */
    public PersistenceException(String message) {
        super(message);
    }

    /**
     * Creates a persistence exception with its original cause.
     *
     * @param message error description
     * @param cause original exception
     */
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}