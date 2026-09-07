package com.clara.adapter.document;

import com.clara.workspace.api.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.Test;

import static org.junit.Assert.*;

public class DocumentParserContractTest {
    @Test
    public void parsesUtf8TextIntoStableLineNodes() {
        TextDocumentParser parser = new TextDocumentParser();
        ParsedDocument value = parser.parse(file("sample.java", "text/plain", "one\ntwo".getBytes(StandardCharsets.UTF_8)));
        assertEquals(DocumentFormat.CODE, value.getFormat());
        assertEquals("line/2", value.getNodes().get(1).getNodeId());
        assertEquals("two", value.getNodes().get(1).getText());
    }

    @Test
    public void parsesDocxParagraphsAndTables() throws Exception {
        XWPFDocument document = new XWPFDocument();
        document.createParagraph().createRun().setText("paragraph");
        XWPFTable table = document.createTable(1, 1);
        table.getRow(0).getCell(0).setText("cell");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.write(out);
        document.close();
        ParsedDocument value = new PoiWordDocumentParser().parse(file("sample.docx", "docx", out.toByteArray()));
        assertEquals(DocumentFormat.WORD, value.getFormat());
        assertTrue(texts(value).contains("paragraph"));
        assertTrue(texts(value).contains("cell"));
    }

    @Test
    public void parsesExcelValuesAndPreservesFormula() throws Exception {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Data");
        Row row = sheet.createRow(0);
        row.createCell(0).setCellValue("value");
        row.createCell(1).setCellFormula("1+1");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();
        ParsedDocument value = new PoiExcelDocumentParser().parse(file("sample.xlsx", "xlsx", out.toByteArray()));
        assertTrue(texts(value).contains("value"));
        assertTrue(texts(value).contains("=1+1"));
    }

    @Test
    public void parsesPdfPerPage() throws Exception {
        PDDocument document = new PDDocument();
        document.addPage(new PDPage());
        PDPageContentStream content = new PDPageContentStream(document, document.getPage(0));
        content.beginText();
        content.setFont(PDType1Font.HELVETICA, 12);
        content.newLineAtOffset(50, 700);
        content.showText("PDF text");
        content.endText();
        content.close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        document.close();
        ParsedDocument value = new PdfBoxDocumentParser(10).parse(file("sample.pdf", "application/pdf", out.toByteArray()));
        assertEquals(1, value.getNodes().size());
        assertTrue(value.getNodes().get(0).getText().contains("PDF text"));
    }

    private static FileDocument file(String path, String mime, byte[] bytes) {
        return new FileDocument(new com.clara.workspace.api.FileDescriptor(path, mime, bytes.length, 1L, "sha"), bytes);
    }

    private static List<String> texts(ParsedDocument value) {
        List<String> result = new ArrayList<String>();
        for (DocumentNode node : value.getNodes()) result.add(node.getText());
        return result;
    }
}
