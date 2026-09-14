package dev.diegogarcia.openpdfpoc;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * PDFBox-rendering viewer engine.
 *
 * <p>Password detection/validation is still done through OpenPDF's
 * {@link com.lowagie.text.pdf.PdfReader} via {@link PasswordResolver} - that
 * is the whole point of this POC, and stays the same regardless of which
 * engine ends up drawing the page. Once a password is confirmed to work (or
 * the document turns out not to need one), the same file is reopened with
 * Apache PDFBox's {@link PDDocument}, and each page is rasterized to an
 * image via {@link PDFRenderer} for a real, pixel-accurate view - something
 * OpenPDF alone cannot do, since it has no page rasterizer.</p>
 */
public class PdfBoxRenderViewerApp extends AbstractPdfViewerApp {

    private static final int RENDER_DPI = 120;

    private final JLabel pageImageLabel = new JLabel();
    private PDDocument document;
    private PDFRenderer renderer;

    public PdfBoxRenderViewerApp() {
        super("OpenPDF Viewer (POC) [PDFBox render]");
        initializeUi();
    }

    @Override
    protected JComponent buildContentPanel() {
        pageImageLabel.setHorizontalAlignment(SwingConstants.CENTER);

        // A neutral surround behind the page, like a document viewer, so a
        // page narrower than the window doesn't look like it's floating on
        // stark white.
        JPanel backdrop = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 20));
        backdrop.setBackground(new Color(0x555555));
        backdrop.add(pageImageLabel);

        JScrollPane scrollPane = new JScrollPane(backdrop);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        return scrollPane;
    }

    @Override
    protected DocumentSummary openDocument(File file) throws Exception {
        PasswordResolver.Resolved resolved = PasswordResolver.resolve(this, file, isBytePatchEnabled());
        try {
            boolean encrypted = resolved.reader.isEncrypted();
            Map<String, String> info = resolved.reader.getInfo();
            String title = info != null ? info.get("Title") : null;
            int pageCount = resolved.reader.getNumberOfPages();

            // OpenPDF's PdfReader was only needed to detect/validate the
            // password; the same password (encoded the same way OpenPDF
            // wrote it) is handed to PDFBox, which does the actual page
            // rendering from here on.
            String password = resolved.password != null
                    ? new String(resolved.password, StandardCharsets.ISO_8859_1)
                    : "";
            document = PDDocument.load(file, password);
            renderer = new PDFRenderer(document);

            return new DocumentSummary(pageCount, encrypted, title);
        } finally {
            resolved.reader.close();
        }
    }

    @Override
    protected void renderPage(int pageNumber) {
        try {
            BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, RENDER_DPI);
            pageImageLabel.setIcon(new ImageIcon(image));
            pageImageLabel.setText(null);
        } catch (IOException e) {
            pageImageLabel.setIcon(null);
            pageImageLabel.setText("Could not render page " + pageNumber + ": " + e.getMessage());
        }
    }

    @Override
    protected void closeDocument() {
        if (document != null) {
            try {
                document.close();
            } catch (IOException ignored) {
                // Nothing meaningful to do about a failure to close a document we're discarding anyway.
            }
            document = null;
            renderer = null;
        }
    }
}
