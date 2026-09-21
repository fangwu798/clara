package com.clara.adapter.document;

import com.clara.kernel.exception.ClaraException;
import com.clara.kernel.exception.ErrorCategory;
import com.clara.kernel.exception.ErrorCodes;
import com.clara.workspace.api.*;

import java.util.*;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * 将PDFBox文档数据解析为领域对象。
 */
public final class PdfBoxDocumentParser implements DocumentParser {
    private final int maxPages;

    public PdfBoxDocumentParser(int maxPages) {
        if (maxPages <= 0) {
            throw new IllegalArgumentException();
        }
        this.maxPages = maxPages;
    }

    public boolean supports(com.clara.workspace.api.FileDescriptor f) {
        return f.getRelativePath().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    public ParsedDocument parse(FileDocument file) {
        try {
            PDDocument document = PDDocument.load(file.getContent());
            try {
                if (document.isEncrypted()) {
                    throw DocumentParsingErrors.error(ErrorCodes.DOCUMENT_ENCRYPTED, ErrorCategory.AUTHORIZATION);
                }
                if (document.getNumberOfPages() > maxPages) {
                    throw DocumentParsingErrors.error(ErrorCodes.WORKSPACE_RESOURCE_LIMIT, ErrorCategory.RESOURCE_LIMIT);
                }
                List<DocumentNode> nodes = new ArrayList<DocumentNode>();
                PDFTextStripper stripper = new PDFTextStripper();
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    Map<String, String> metadata = Collections.singletonMap("page", String.valueOf(page));
                    nodes.add(new DocumentNode("page/" + page, DocumentNodeType.PAGE, stripper.getText(document), metadata));
                }
                boolean scanned = true;
                for (DocumentNode node : nodes)
                    if (!node.getText().trim().isEmpty()) {
                        scanned = false;
                        break;
                    }
                List<String> warnings = scanned ? Collections.singletonList("PDF contains no extractable text; OCR is required") : Collections.<String>emptyList();
                return new ParsedDocument(DocumentFormat.PDF, nodes, Collections.<String, String>emptyMap(), false, warnings);
            } finally {
                document.close();
            }
        } catch (ClaraException e) {
            throw e;
        } catch (Exception e) {
            String name = e.getClass().getName();
            if (name.contains("InvalidPassword")) {
                throw DocumentParsingErrors.error(ErrorCodes.DOCUMENT_ENCRYPTED, ErrorCategory.AUTHORIZATION);
            }
            throw DocumentParsingErrors.error(ErrorCodes.DOCUMENT_PARSE_FAILED, ErrorCategory.VALIDATION);
        }
    }
}
