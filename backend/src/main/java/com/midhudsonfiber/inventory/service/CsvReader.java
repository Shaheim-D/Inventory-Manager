package com.midhudsonfiber.inventory.service;

import com.midhudsonfiber.inventory.web.ApiExceptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A CSV reader that handles what a spreadsheet actually produces: quoted
 * fields, embedded commas and newlines, and doubled quotes as an escape.
 *
 * <p>Splitting on commas would corrupt any row containing an address or a note,
 * silently and in a way that only shows up later. Written rather than pulled in
 * because the whole of it is below and the project already avoids dependencies
 * it does not need.
 */
final class CsvReader {

    private CsvReader() {}

    record Row(int lineNumber, Map<String, String> values) {}

    /** Header row plus data rows, with values already matched to column names. */
    record Sheet(List<String> headers, List<Row> rows) {}

    static Sheet read(InputStream input, int maxRows) {
        List<List<String>> records = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            parse(reader, records);
        } catch (IOException e) {
            throw new ApiExceptions.BadRequestException("Could not read that file: " + e.getMessage());
        }

        if (records.isEmpty()) {
            throw new ApiExceptions.BadRequestException("That file is empty.");
        }

        List<String> headers = records.get(0).stream()
                .map(header -> header.replace("﻿", "").trim())
                .toList();
        if (headers.stream().allMatch(String::isBlank)) {
            throw new ApiExceptions.BadRequestException("The first row must name the columns.");
        }

        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            // A trailing newline is normal, and a row of empty cells is not
            // something to report as a failure.
            if (record.stream().allMatch(String::isBlank)) continue;

            Map<String, String> values = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                String header = headers.get(column);
                if (header.isBlank()) continue;
                values.put(header, column < record.size() ? record.get(column).trim() : "");
            }
            // +1 because the header is line 1: the number has to match what the
            // person sees in their spreadsheet.
            rows.add(new Row(i + 1, values));

            if (rows.size() > maxRows) {
                throw new ApiExceptions.BadRequestException(
                        "That file has more than %,d rows. Split it and import in batches."
                                .formatted(maxRows));
            }
        }
        return new Sheet(headers, rows);
    }

    private static void parse(BufferedReader reader, List<List<String>> records) throws IOException {
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int read;

        while ((read = reader.read()) != -1) {
            char c = (char) read;

            if (quoted) {
                if (c == '"') {
                    // A doubled quote inside a quoted field is a literal quote.
                    reader.mark(1);
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        if (next != -1) reader.reset();
                    }
                } else {
                    // Newlines inside quotes belong to the value -- this is the
                    // case that splitting on lines would get wrong.
                    field.append(c);
                }
                continue;
            }

            switch (c) {
                case '"' -> quoted = true;
                case ',' -> {
                    current.add(field.toString());
                    field.setLength(0);
                }
                case '\r' -> { /* handled by the \n that follows it */ }
                case '\n' -> {
                    current.add(field.toString());
                    field.setLength(0);
                    records.add(current);
                    current = new ArrayList<>();
                }
                default -> field.append(c);
            }
        }

        // Whatever was in hand when the file ended is still a row.
        if (!field.isEmpty() || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current);
        }
    }
}
