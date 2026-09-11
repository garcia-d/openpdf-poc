package dev.diegogarcia.openpdfpoc;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.Font;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Pure-OpenPDF viewer engine.
 *
 * <p>OpenPDF does not include a page rasterizer (it is primarily a PDF
 * generation/manipulation library, not a rendering engine), so "viewing"
 * here means opening the document with {@link PdfReader} and displaying
 * each page's extracted text via {@link PdfTextExtractor}. This is enough to
 * demonstrate the part this proof of concept actually cares about: detecting
 * that a PDF requires a password to open, prompting the user for it, and
 * retrying until OpenPDF accepts either the document's user password or its
 * owner password (OpenPDF exposes both through the same single-password
 * API). See {@link PdfBoxRenderViewerApp} for a real, pixel-rendered page
 * view built on Apache PDFBox.</p>
 */
public class OpenPdfTextViewerApp extends AbstractPdfViewerApp {

    private final JTextArea contentArea = new JTextArea();
    private PdfReader currentReader;

    public OpenPdfTextViewerApp() {
        super("OpenPDF Viewer (POC) [text]");
        initializeUi();
    }

    @Override
    protected JComponent buildContentPanel() {
        contentArea.setEditable(false);
        contentArea.setLineWrap(true);
        contentArea.setWrapStyleWord(true);
        // Serif reads better for extracted prose than a monospaced font, which
        // has no benefit here (there's no code/columns to align) and is a
        // less common font choice for other locales.
        contentArea.setFont(new Font(Font.SERIF, Font.PLAIN, 15));
        contentArea.setMargin(new Insets(24, 32, 24, 32));
        return new JScrollPane(contentArea);
    }

    @Override
    protected DocumentSummary openDocument(File file) throws Exception {
        PasswordResolver.Resolved resolved = PasswordResolver.resolve(this, file);
        currentReader = resolved.reader;

        Map<String, String> info = currentReader.getInfo();
        String title = info != null ? info.get("Title") : null;
        return new DocumentSummary(currentReader.getNumberOfPages(), currentReader.isEncrypted(), title);
    }

    @Override
    protected void renderPage(int pageNumber) {
        try {
            String text = new PdfTextExtractor(currentReader).getTextFromPage(pageNumber);
            if (text == null || text.trim().isEmpty()) {
                text = "[Page " + pageNumber + " has no extractable text. OpenPDF can read PDF "
                        + "structure and text but does not rasterize page images, so pages that are "
                        + "purely graphical (e.g. scanned pages) show no content here.]";
            }
            contentArea.setText(normalizeForDisplay(text));
            contentArea.setCaretPosition(0);
        } catch (IOException e) {
            contentArea.setText("Could not extract text from page " + pageNumber + ": " + e.getMessage());
        }
    }

    @Override
    protected void closeDocument() {
        if (currentReader != null) {
            currentReader.close();
            currentReader = null;
        }
    }

    /**
     * Replaces "smart" typographic punctuation (curly quotes, en/em dashes,
     * ellipsis, bullets, non-breaking spaces) with its plain-ASCII
     * equivalent before display.
     *
     * <p>This works around a font-rendering bug observed on this JDK 8 +
     * Aqua look-and-feel combination: whenever a line of text mixes such a
     * character with ordinary Latin letters, Swing's text rendering silently
     * drops random neighboring letters from that line (confirmed by
     * isolating it down to individual characters - e.g. a lone "&#x2026;" in
     * "Open PDF&#x2026;" was enough to blank out the "P" in "PDF"; removing
     * it fixed the button reliably across repeated runs). Real-world PDFs
     * (anything exported from Word/Office in particular) are full of these
     * characters, so page text needs the same treatment as the UI's own
     * labels. This is a compatibility workaround, not a design preference -
     * it does not help with genuinely non-Latin scripts (CJK, Arabic, etc.),
     * which can still hit the same underlying bug; that is a limitation of
     * this JDK 8/platform combination that pure application code cannot
     * fully work around.</p>
     */
    private static String normalizeForDisplay(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '‘': case '’': case '‚': case '‛':
                    sb.append('\'');
                    break;
                case '“': case '”': case '„': case '‟':
                    sb.append('"');
                    break;
                case '–': // en dash
                    sb.append('-');
                    break;
                case '—': // em dash
                    sb.append("--");
                    break;
                case '…': // ellipsis
                    sb.append("...");
                    break;
                case '•': case '●': case '▪': // bullets
                    sb.append('-');
                    break;
                case ' ': // non-breaking space
                    sb.append(' ');
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
