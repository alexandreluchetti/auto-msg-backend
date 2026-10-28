package br.com.conversa.app.business;

import java.util.List;

/**
 * A spreadsheet that cannot be imported. Nothing of that file is stored.
 */
public class CsvImportException extends RuntimeException {

    private final String file;
    private final List<String> errors;

    public CsvImportException(String file, List<String> errors) {
        super("Cannot import " + file + ": " + String.join("; ", errors));
        this.file = file;
        this.errors = List.copyOf(errors);
    }

    public String file() {
        return file;
    }

    public List<String> errors() {
        return errors;
    }
}
