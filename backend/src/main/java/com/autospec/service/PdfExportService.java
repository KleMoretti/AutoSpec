package com.autospec.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Service
public class PdfExportService {

    private static final float FONT_SIZE = 10F;
    private static final float LEADING = 14F;
    private static final float MARGIN = 48F;
    private static final float START_Y = 780F;
    private static final float END_Y = 48F;
    private static final int MAX_LINE_LENGTH = 95;

    private final MarkdownExportService markdownExportService;

    public PdfExportService(MarkdownExportService markdownExportService) {
        this.markdownExportService = markdownExportService;
    }

    public byte[] exportProject(Long projectId) {
        String markdown = markdownExportService.exportProject(projectId);
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PageWriter writer = new PageWriter(document, font);
            for (String line : markdown.split("\\R", -1)) {
                for (String wrapped : wrap(sanitizePdfText(line))) {
                    writer.writeLine(wrapped);
                }
            }
            writer.close();
            document.save(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "PDF export failed", ex);
        }
    }

    private java.util.List<String> wrap(String line) {
        if (line.length() <= MAX_LINE_LENGTH) {
            return java.util.List.of(line);
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (int start = 0; start < line.length(); start += MAX_LINE_LENGTH) {
            lines.add(line.substring(start, Math.min(start + MAX_LINE_LENGTH, line.length())));
        }
        return lines;
    }

    private String sanitizePdfText(String line) {
        StringBuilder result = new StringBuilder();
        for (char value : line.toCharArray()) {
            result.append(value >= 32 && value <= 126 ? value : '?');
        }
        return result.toString();
    }

    private static final class PageWriter implements AutoCloseable {
        private final PDDocument document;
        private final PDType1Font font;
        private PDPageContentStream stream;
        private float y = START_Y;

        private PageWriter(PDDocument document, PDType1Font font) throws IOException {
            this.document = document;
            this.font = font;
            newPage();
        }

        private void writeLine(String line) throws IOException {
            if (y <= END_Y) {
                closeStream();
                newPage();
            }
            stream.showText(line);
            stream.newLine();
            y -= LEADING;
        }

        private void newPage() throws IOException {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            stream.beginText();
            stream.setFont(font, FONT_SIZE);
            stream.setLeading(LEADING);
            stream.newLineAtOffset(MARGIN, START_Y);
            y = START_Y;
        }

        @Override
        public void close() throws IOException {
            closeStream();
        }

        private void closeStream() throws IOException {
            if (stream != null) {
                stream.endText();
                stream.close();
                stream = null;
            }
        }
    }
}
