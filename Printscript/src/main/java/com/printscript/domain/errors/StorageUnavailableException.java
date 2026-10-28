package domain.errors;

/** El almacenamiento (S3) no responde o fallo. El controller deberia mapearla a 502/503. */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}