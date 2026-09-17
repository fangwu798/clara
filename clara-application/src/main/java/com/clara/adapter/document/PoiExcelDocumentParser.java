package com.clara.adapter.document;

import com.clara.kernel.*;
import com.clara.workspace.api.*;

import java.io.*;
import java.util.*;

import org.apache.poi.ss.usermodel.*;

/**
 * 将POIExcel文档数据解析为领域对象。
 */
public final class PoiExcelDocumentParser implements DocumentParser {
    public boolean supports(com.clara.workspace.api.FileDescriptor f) {
        String p = f.getRelativePath().toLowerCase(Locale.ROOT);
        return p.endsWith(".xlsx") || p.endsWith(".xls");
    }

    public ParsedDocument parse(FileDocument file) {
        try {
            Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file.getContent()));
            try {
                List<DocumentNode> nodes = new ArrayList<DocumentNode>();
                DataFormatter formatter = new DataFormatter(Locale.ROOT);
                for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                    Sheet sheet = workbook.getSheetAt(s);
                    nodes.add(new DocumentNode("sheet/" + s, DocumentNodeType.SHEET, sheet.getSheetName(), Collections.<String, String>emptyMap()));
                    for (Row row : sheet) {
                        for (Cell cell : row) {
                            String text = cell.getCellType() == CellType.FORMULA ? "=" + cell.getCellFormula() : formatter.formatCellValue(cell);
                            Map<String, String> metadata = new LinkedHashMap<String, String>();
                            metadata.put("sheet", sheet.getSheetName());
                            metadata.put("row", String.valueOf(row.getRowNum()));
                            metadata.put("column", String.valueOf(cell.getColumnIndex()));
                            nodes.add(new DocumentNode("sheet/" + s + "/cell/" + row.getRowNum() + "/" + cell.getColumnIndex(), DocumentNodeType.CELL, text, metadata));
                        }
                    }
                }
                return new ParsedDocument(DocumentFormat.EXCEL, nodes, Collections.<String, String>emptyMap(), true, Collections.singletonList("formulas are preserved and not evaluated"));
            } finally {
                workbook.close();
            }
        } catch (Exception e) {
            throw DocumentParsingErrors.error(ErrorCodes.DOCUMENT_PARSE_FAILED, ErrorCategory.VALIDATION);
        }
    }
}
