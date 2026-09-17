package com.clara.adapter.document;

import com.clara.kernel.*;
import com.clara.workspace.api.*;

import java.io.*;
import java.util.*;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.*;

/**
 * 将POIWord文档数据解析为领域对象。
 */
public final class PoiWordDocumentParser implements DocumentParser {
    public boolean supports(com.clara.workspace.api.FileDescriptor f) {
        String p = f.getRelativePath().toLowerCase(Locale.ROOT);
        return p.endsWith(".docx") || p.endsWith(".doc");
    }

    public ParsedDocument parse(FileDocument file) {
        String p = file.getDescriptor().getRelativePath().toLowerCase(Locale.ROOT);
        try {
            return p.endsWith(".docx") ? docx(file) : doc(file);
        } catch (Exception e) {
            throw DocumentParsingErrors.error(ErrorCodes.DOCUMENT_PARSE_FAILED, ErrorCategory.VALIDATION);
        }
    }

    private ParsedDocument docx(FileDocument file) throws IOException {
        XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(file.getContent()));
        try {
            List<DocumentNode> nodes = new ArrayList<DocumentNode>();
            int i = 0;
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                DocumentNodeType type = paragraph.getStyle() != null && paragraph.getStyle().toLowerCase(Locale.ROOT).contains("heading") ? DocumentNodeType.HEADING : DocumentNodeType.PARAGRAPH;
                nodes.add(new DocumentNode("paragraph/" + (i++), type, paragraph.getText(), Collections.<String, String>emptyMap()));
            }
            int t = 0;
            for (XWPFTable table : document.getTables()) {
                int r = 0;
                for (XWPFTableRow row : table.getRows()) {
                    int c = 0;
                    for (XWPFTableCell cell : row.getTableCells())
                        nodes.add(new DocumentNode("table/" + t + "/row/" + r + "/cell/" + (c++), DocumentNodeType.CELL, cell.getText(), Collections.<String, String>emptyMap()));
                    r++;
                }
                t++;
            }
            return new ParsedDocument(DocumentFormat.WORD, nodes, Collections.<String, String>emptyMap(), true, Collections.<String>emptyList());
        } finally {
            document.close();
        }
    }

    private ParsedDocument doc(FileDocument file) throws IOException {
        HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(file.getContent()));
        WordExtractor extractor = new WordExtractor(document);
        try {
            List<DocumentNode> nodes = new ArrayList<DocumentNode>();
            String[] paragraphs = extractor.getParagraphText();
            for (int i = 0; i < paragraphs.length; i++)
                nodes.add(new DocumentNode("paragraph/" + i, DocumentNodeType.PARAGRAPH, paragraphs[i], Collections.<String, String>emptyMap()));
            return new ParsedDocument(DocumentFormat.WORD, nodes, Collections.<String, String>emptyMap(), false, Collections.singletonList("legacy DOC is read-only"));
        } finally {
            extractor.close();
            document.close();
        }
    }
}
