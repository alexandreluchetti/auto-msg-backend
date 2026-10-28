package br.com.conversa.app.business;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A spreadsheet exported as CSV (RFC 4180): header row, quoted fields, quotes doubled inside
 * them. The separator is {@code ;} or {@code ,}, whichever the header uses (Excel in pt-BR saves
 * with {@code ;}). Lines with only empty fields are skipped; a UTF-8 BOM is ignored.
 */
final class CsvTable {

    /** One data row; {@code line} is the line number in the file, for error messages. */
    record Row(int line, Map<String, String> values) {

        /** Trimmed value; empty when the column is missing or blank. */
        String get(String column) {
            String value = values.get(column);
            return value == null ? "" : value.trim();
        }
    }

    private final List<Row> rows;

    private CsvTable(List<Row> rows) {
        this.rows = rows;
    }

    List<Row> rows() {
        return rows;
    }

    /**
     * @throws CsvImportException if the header lacks a required column, has an unknown or
     *                            repeated one, or a quote is not closed
     */
    static CsvTable parse(String file, String content, Set<String> required, Set<String> optional) {
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        char separator = detectSeparator(text);
        List<List<String>> records = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        split(text, separator, records, lines, errors);
        if (!errors.isEmpty()) {
            throw new CsvImportException(file, errors);
        }
        if (records.isEmpty()) {
            throw new CsvImportException(file, List.of("the file is empty; a header row is required"));
        }

        List<String> header = records.getFirst().stream().map(column -> column.trim().toLowerCase(Locale.ROOT)).toList();
        Set<String> seen = new LinkedHashSet<>();
        for (String column : header) {
            if (!seen.add(column)) {
                errors.add("column '" + column + "' appears twice in the header");
            } else if (!required.contains(column) && !optional.contains(column)) {
                errors.add("unknown column '" + column + "'; expected " + expected(required, optional));
            }
        }
        required.stream().filter(column -> !seen.contains(column))
                .forEach(column -> errors.add("missing column '" + column + "'"));
        if (!errors.isEmpty()) {
            throw new CsvImportException(file, errors);
        }

        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            if (record.size() > header.size()) {
                errors.add("line " + lines.get(i) + ": " + record.size() + " fields, the header has " + header.size());
                continue;
            }
            Map<String, String> values = new HashMap<>();
            for (int c = 0; c < record.size(); c++) {
                values.put(header.get(c), record.get(c));
            }
            rows.add(new Row(lines.get(i), values));
        }
        if (!errors.isEmpty()) {
            throw new CsvImportException(file, errors);
        }
        return new CsvTable(rows);
    }

    private static String expected(Set<String> required, Set<String> optional) {
        List<String> all = new ArrayList<>(required);
        all.addAll(optional);
        return String.join(", ", all);
    }

    private static char detectSeparator(String text) {
        int end = text.indexOf('\n');
        String header = end < 0 ? text : text.substring(0, end);
        long semicolons = header.chars().filter(c -> c == ';').count();
        long commas = header.chars().filter(c -> c == ',').count();
        return semicolons > commas ? ';' : ',';
    }

    private static void split(String text, char separator, List<List<String>> records, List<Integer> lines,
                              List<String> errors) {
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int recordLine = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == separator) {
                record.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(field.toString());
                if (record.stream().anyMatch(value -> !value.isBlank())) {
                    records.add(record);
                    lines.add(recordLine);
                }
                record = new ArrayList<>();
                field.setLength(0);
                line++;
                recordLine = line;
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            errors.add("line " + recordLine + ": a quoted field is not closed");
            return;
        }
        record.add(field.toString());
        if (record.stream().anyMatch(value -> !value.isBlank())) {
            records.add(record);
            lines.add(recordLine);
        }
    }
}
