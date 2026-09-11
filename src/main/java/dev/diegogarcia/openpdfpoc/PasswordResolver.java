package dev.diegogarcia.openpdfpoc;

import com.lowagie.text.exceptions.BadPasswordException;
import com.lowagie.text.pdf.PdfReader;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.io.IOException;
import java.util.Arrays;

/**
 * Opens a PDF with OpenPDF's {@link PdfReader}, prompting the user for a
 * password (up to {@link #MAX_ATTEMPTS} times) if the document requires one.
 *
 * <p>This is the one place in the application that deals with OpenPDF's
 * single-password model: {@link PdfReader} accepts either the document's
 * owner (edit/change) password or its user (open/view) password through the
 * same API, without distinguishing which one was supplied. Both viewer
 * engines - the pure-OpenPDF text view and the PDFBox-rendering view - use
 * this class to detect and validate the password, so that check is always
 * done through OpenPDF regardless of which engine ends up rendering the
 * page.</p>
 */
final class PasswordResolver {

    private static final int MAX_ATTEMPTS = 5;

    private PasswordResolver() {
    }

    /** Result of successfully opening a document: the reader, and the password that worked (null if none was needed). */
    static final class Resolved {
        final PdfReader reader;
        final byte[] password;

        private Resolved(PdfReader reader, byte[] password) {
            this.reader = reader;
            this.password = password;
        }
    }

    /** Thrown when the user cancels a password prompt instead of entering one. */
    static final class PasswordEntryCancelledException extends IOException {
    }

    /**
     * Runs on a background thread. Tries to open the PDF without a password
     * first; if OpenPDF signals that a password is required, repeatedly asks
     * the user (on the EDT, via a modal dialog) until it opens, the user
     * cancels ({@link PasswordEntryCancelledException}), or the attempt limit
     * is reached ({@link IOException}).
     *
     * @param parent component to center password dialogs on
     * @param file   the PDF file to open
     */
    static Resolved resolve(Component parent, java.io.File file) throws IOException {
        try {
            return new Resolved(new PdfReader(file.getAbsolutePath()), null);
        } catch (BadPasswordException firstAttempt) {
            String message = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                char[] password = promptForPassword(parent, file.getName(), message);
                if (password == null) {
                    throw new PasswordEntryCancelledException();
                }
                byte[] bytes = toBytes(password);
                try {
                    return new Resolved(new PdfReader(file.getAbsolutePath(), bytes), bytes);
                } catch (BadPasswordException wrongPassword) {
                    message = "Incorrect password. Please try again.";
                }
            }
            throw new IOException("Too many incorrect password attempts.");
        }
    }

    /** Must be invoked from a background thread; blocks until the EDT dialog is dismissed. */
    private static char[] promptForPassword(Component parent, String fileName, String errorMessage) {
        final char[][] result = new char[1][];
        try {
            SwingUtilities.invokeAndWait(() -> {
                JPasswordField passwordField = new JPasswordField(20);
                JPanel panel = new JPanel(new BorderLayout(0, 6));
                String prompt = "\"" + fileName + "\" is password protected.\nEnter the password to open it:";
                panel.add(new JLabel("<html>" + prompt.replace("\n", "<br>") + "</html>"), BorderLayout.NORTH);
                panel.add(passwordField, BorderLayout.CENTER);
                if (errorMessage != null) {
                    JLabel error = new JLabel(errorMessage);
                    error.setForeground(Color.RED);
                    panel.add(error, BorderLayout.SOUTH);
                }

                int option = JOptionPane.showConfirmDialog(
                        parent, panel, "Password required",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

                result[0] = option == JOptionPane.OK_OPTION ? passwordField.getPassword() : null;
            });
        } catch (Exception e) {
            result[0] = null;
        }
        return result[0];
    }

    private static byte[] toBytes(char[] chars) {
        byte[] bytes = new byte[chars.length];
        for (int i = 0; i < chars.length; i++) {
            bytes[i] = (byte) chars[i];
        }
        Arrays.fill(chars, '\0');
        return bytes;
    }
}
