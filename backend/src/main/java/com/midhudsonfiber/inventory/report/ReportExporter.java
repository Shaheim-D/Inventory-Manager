package com.midhudsonfiber.inventory.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Getting a report out of the application.
 *
 * <p>Two formats for two different jobs, which is why there are two and not
 * three: CSV is data somebody will open in a spreadsheet and work with, PDF is
 * a document somebody will hand to a person. A .xlsx writer would be a third
 * way of doing the first job.
 */
@Component
public class ReportExporter {

    /**
     * CSV, quoted properly.
     *
     * <p>A UTF-8 byte order mark leads the file. It is not decoration: without
     * it Excel on Windows reads a UTF-8 CSV as the system codepage, and every
     * vendor name with an accent in it arrives mangled. Every other consumer
     * ignores it.
     */
    public byte[] toCsv(ReportService.Result result) {
        StringBuilder out = new StringBuilder("﻿");
        out.append(String.join(",", result.columns().stream()
                .map(column -> quote(column.label())).toList()));
        out.append("\r\n");

        for (Map<String, Object> row : result.rows()) {
            out.append(String.join(",", result.columns().stream()
                    .map(column -> quote(render(row.get(column.key())))).toList()));
            out.append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A landscape table, paginated, with the report's name and the date it was
     * produced at the top of every page — a printed report with no date on it is
     * a report nobody can trust six months later.
     */
    public byte[] toPdf(ReportService.Result result) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var regular = new org.apache.pdfbox.pdmodel.font.PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            float margin = 30f;
            PDRectangle size = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            float usableWidth = size.getWidth() - margin * 2;
            // Even columns. Measuring content to size them would be better and is
            // not worth the complexity for a report that exists so somebody can
            // read it on paper -- CSV is the format for working with the data.
            float columnWidth = usableWidth / Math.max(1, result.columns().size());
            float rowHeight = 14f;

            int rowIndex = 0;
            while (rowIndex < result.rows().size() || rowIndex == 0) {
                PDPage page = new PDPage(size);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    float y = size.getHeight() - margin;

                    content.beginText();
                    content.setFont(bold, 13);
                    content.newLineAtOffset(margin, y);
                    content.showText(sanitise(result.title()));
                    content.endText();

                    content.beginText();
                    content.setFont(regular, 8);
                    content.newLineAtOffset(margin, y - 13);
                    content.showText("Generated " + LocalDate.now() + " — " + result.rows().size()
                            + " row(s)" + (result.truncated() ? ", truncated" : ""));
                    content.endText();

                    y -= 34;
                    content.setFont(bold, 8);
                    float x = margin;
                    for (ReportService.Column column : result.columns()) {
                        content.beginText();
                        content.newLineAtOffset(x, y);
                        content.showText(clip(sanitise(column.label()), columnWidth, 8, bold));
                        content.endText();
                        x += columnWidth;
                    }
                    y -= 4;
                    content.moveTo(margin, y);
                    content.lineTo(size.getWidth() - margin, y);
                    content.stroke();
                    y -= rowHeight;

                    content.setFont(regular, 8);
                    while (rowIndex < result.rows().size() && y > margin) {
                        Map<String, Object> row = result.rows().get(rowIndex);
                        x = margin;
                        for (ReportService.Column column : result.columns()) {
                            content.beginText();
                            content.newLineAtOffset(x, y);
                            content.showText(clip(sanitise(render(row.get(column.key()))),
                                    columnWidth, 8, regular));
                            content.endText();
                            x += columnWidth;
                        }
                        y -= rowHeight;
                        rowIndex++;
                    }
                }
                if (rowIndex >= result.rows().size()) break;
            }

            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String render(Object value) {
        if (value == null) return "";
        if (value instanceof List<?> list) {
            return String.join(", ", list.stream().map(String::valueOf).toList());
        }
        if (value instanceof Boolean flag) return flag ? "Yes" : "No";
        return String.valueOf(value);
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains("\"") || safe.contains(",") || safe.contains("\n") || safe.contains("\r")) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    /**
     * The standard 14 PDF fonts are WinAnsi, and handing them a character they
     * cannot encode throws rather than degrades. Report data is user-entered, so
     * that would mean an export failing on somebody's name.
     */
    private static String sanitise(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '\t') out.append(' ');
            else if (c >= 32 && c <= 255) out.append(c);
            else if (c == '—' || c == '–') out.append('-');
            else if (c == '‘' || c == '’') out.append('\'');
            else if (c == '“' || c == '”') out.append('"');
            else out.append('?');
        }
        return out.toString();
    }

    private static String clip(String text, float width, float fontSize,
                               org.apache.pdfbox.pdmodel.font.PDFont font) {
        try {
            String candidate = text;
            float allowed = width - 4;
            while (!candidate.isEmpty()
                    && font.getStringWidth(candidate) / 1000 * fontSize > allowed) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            return candidate.length() < text.length() && candidate.length() > 1
                    ? candidate.substring(0, candidate.length() - 1) + "…".replace("…", ".")
                    : candidate;
        } catch (IOException e) {
            return text;
        }
    }
}
