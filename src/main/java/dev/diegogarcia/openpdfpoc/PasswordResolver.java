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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * <p>This class also works around a known OpenPDF 1.3.x parsing bug (see
 * {@link #patchOutOfRangePermissions}) that makes some PDFs with no real
 * user password at all - e.g. exported by tools that write the
 * {@code /Encrypt} dictionary's {@code /P} permissions entry as an unsigned
 * 32-bit decimal - falsely appear password-protected to {@code PdfReader}.
 * See the README, "Known OpenPDF quirk: out-of-range /P permissions". That
 * patch is applied only when {@link #resolve}'s {@code applyKnownBugPatch}
 * parameter is {@code true} (wired to a checkbox both viewer engines share),
 * so a patched OpenPDF build can be tested with it turned off.</p>
 */
final class PasswordResolver {

    private static final int MAX_ATTEMPTS = 5;

    /** Matches the encryption dictionary's filter name, to scope the /P search below to it. */
    private static final Pattern STANDARD_SECURITY_HANDLER = Pattern.compile("/Filter\\s*/Standard");

    /** Matches a /P entry's value; searched for only within a window after the marker above. */
    private static final Pattern PERMISSIONS_ENTRY = Pattern.compile("/P\\s+(-?\\d+)\\b");

    /** How far past the /Filter/Standard marker to look for the /P entry. Generous but bounded. */
    private static final int ENCRYPT_DICT_SEARCH_WINDOW = 1024;

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
     * @param parent          component to center password dialogs on
     * @param file            the PDF file to open
     * @param applyKnownBugPatch whether to try {@link #patchOutOfRangePermissions} as
     *                        a fallback when the first, unmodified open attempt fails
     *                        with {@link BadPasswordException}. Wired to a checkbox in
     *                        {@link AbstractPdfViewerApp}'s shared top panel, checked
     *                        by default; pass {@code false} to open the file as-is
     *                        instead - e.g. to test whether a patched OpenPDF build no
     *                        longer needs this workaround at all.
     */
    static Resolved resolve(Component parent, java.io.File file, boolean applyKnownBugPatch) throws IOException {
        byte[] originalBytes = Files.readAllBytes(file.toPath());
        try {
            return new Resolved(new PdfReader(originalBytes), null);
        } catch (BadPasswordException firstAttempt) {
            byte[] patchedBytes = applyKnownBugPatch ? patchOutOfRangePermissions(originalBytes) : null;
            if (patchedBytes != null) {
                try {
                    // No password field on this result: the document was never
                    // really password-protected, OpenPDF just misparsed /P.
                    return new Resolved(new PdfReader(patchedBytes), null);
                } catch (IOException stillBad) {
                    // Not this bug after all (or it's a genuinely password-protected
                    // document too) - fall through and prompt normally below.
                }
            }

            String message = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                char[] password = promptForPassword(parent, file.getName(), message);
                if (password == null) {
                    throw new PasswordEntryCancelledException();
                }
                byte[] bytes = toBytes(password);
                try {
                    return new Resolved(new PdfReader(originalBytes, bytes), bytes);
                } catch (BadPasswordException wrongPassword) {
                    message = "Incorrect password. Please try again.";
                }
            }
            throw new IOException("Too many incorrect password attempts.");
        }
    }

    /**
     * Works around a known OpenPDF 1.3.x bug: {@code PdfNumber} stores every
     * PDF number as a Java {@code double}, and {@code PdfNumber#intValue()}
     * narrows it with a plain {@code (int)} cast. Java's narrowing
     * double-to-int conversion <em>saturates</em> rather than wraps for a
     * value outside the {@code int} range, so an {@code /Encrypt}
     * dictionary's {@code /P} (permissions) entry written as the equivalent
     * <em>unsigned</em> 32-bit decimal (e.g. {@code 4294965956}, whose bit
     * pattern is legitimately the signed permissions value {@code -1340})
     * gets misread as {@code Integer.MAX_VALUE} instead of {@code -1340}.
     * That corrupts the encryption key OpenPDF derives for every password
     * attempt - including a correct, empty one - so {@code PdfReader}
     * reports {@link BadPasswordException} for a document that was never
     * actually password-protected.
     *
     * <p>This rewrites just that decimal value, in place, to its correctly
     * signed form. It only touches the plaintext {@code /Encrypt} dictionary
     * bytes (never encrypted, so safe to edit before any password is known),
     * and only a {@code /P} value found near a {@code /Filter/Standard}
     * marker that is actually out of range - every other file is returned
     * untouched as {@code null}, a no-op.</p>
     *
     * <p>Where it fits, the replacement is zero-padded to the same width as
     * the original digits so no byte offset elsewhere in the file shifts
     * (matching the convention already used by classic PDF xref tables). In
     * the rare case where the signed form needs an extra character, the
     * file's length changes instead; {@code PdfReader} is generally able to
     * recover from a stale {@code startxref} in that situation, but if it
     * can't, {@link #resolve} just falls back to prompting as before.</p>
     *
     * @return patched bytes, or {@code null} if nothing needed patching
     */
    private static byte[] patchOutOfRangePermissions(byte[] pdfBytes) {
        // PDF structural syntax (outside stream bodies) is plain ASCII, and
        // ISO-8859-1 maps each byte to exactly one char, so string offsets
        // below translate directly back to byte offsets for the rewrite.
        String text = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        Matcher filterMatcher = STANDARD_SECURITY_HANDLER.matcher(text);
        if (!filterMatcher.find()) {
            return null;
        }
        int windowEnd = Math.min(text.length(), filterMatcher.end() + ENCRYPT_DICT_SEARCH_WINDOW);
        Matcher pMatcher = PERMISSIONS_ENTRY.matcher(text.substring(filterMatcher.end(), windowEnd));
        if (!pMatcher.find()) {
            return null;
        }

        String original = pMatcher.group(1);
        long value;
        try {
            value = Long.parseLong(original);
        } catch (NumberFormatException notAPlainLong) {
            return null;
        }
        if (value <= Integer.MAX_VALUE || value > 0xFFFFFFFFL) {
            return null; // already in range, or not a plausible 32-bit value at all
        }

        String signed = Long.toString(value - 0x100000000L);
        String replacement = original.length() >= signed.length()
                ? "-" + padWithZeros(signed.substring(1), original.length() - 1)
                : signed;

        int matchStart = filterMatcher.end() + pMatcher.start(1);
        String patchedText = text.substring(0, matchStart) + replacement
                + text.substring(matchStart + original.length());
        return patchedText.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String padWithZeros(String digits, int width) {
        StringBuilder padded = new StringBuilder();
        for (int i = digits.length(); i < width; i++) {
            padded.append('0');
        }
        return padded.append(digits).toString();
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
