package net.anweisen.chronit.core.driver;

/** A session could not be started, or a driver-level operation failed. */
public class DriverException extends Exception {

    public DriverException(String message) {
        super(message);
    }

    public DriverException(String message, Throwable cause) {
        super(message, cause);
    }
}
