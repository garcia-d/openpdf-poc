package dev.diegogarcia.openpdfpoc;

import com.lowagie.text.exceptions.BadPasswordException;
import com.lowagie.text.pdf.PdfReader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens a PDF with OpenPDF's {@link PdfReader}, asking the caller for a
 * password (up to {@link #MAX_ATTEMPTS} times, via the {@link PasswordPrompt}
 * it supplies) if the document requires one.
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
 * <p>This class has no dependency on Swing or any other UI toolkit - it asks
 * for passwords purely through the {@link PasswordPrompt} interface, so it
 * could be reused as-is in a non-Swing (e.g. web or command-line) project.
 * This app's own Swing dialog implementation of that interface is
 * {@link SwingPasswordPrompt}.</p>
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
 *
 * <p>This class - and {@link #patchOutOfRangePermissions} in particular - is
 * also published as a standalone, dependency-free, license-headered file at
 * {@code standalone/PasswordResolver.java} in this repo, for copying into
 * other projects hitting the same OpenPDF bug. Keep the two in sync.</p>
 */
final class PasswordResolver {

    /** How many times {@link #resolve} will ask {@link PasswordPrompt} for a password before giving up. */
    static final int MAX_ATTEMPTS = 5;

    /** Matches the encryption dictionary's filter name, to scope the /P search below to it. */
    private static final Pattern STANDARD_SECURITY_HANDLER = Pattern.compile("/Filter\\s*/Standard");

    /** Matches a /P entry's value; searched for only within a window after the marker above. */
    private static final Pattern PERMISSIONS_ENTRY = Pattern.compile("/P\\s+(-?\\d+)\\b");

    /** How far past the /Filter/Standard marker to look for the /P entry. Generous but bounded. */
    private static final int ENCRYPT_DICT_SEARCH_WINDOW = 1024;

    private PasswordResolver() {
    }

    /**
     * Supplies passwords to try, one call per attempt. Has no dependency on
     * any particular UI toolkit (or any UI at all): implement it however
     * fits your project - a Swing dialog (see {@link SwingPasswordPrompt}), a
     * web request/response round trip, a console prompt via
     * {@code System.console()}, a fixed password read from configuration,
     * etc. {@link #resolve} calls this synchronously, on whatever thread it
     * itself was called from; if your implementation needs to hop to
     * another thread (e.g. a UI thread) to ask, do that inside the
     * implementation and block until it has an answer.
     */
    @FunctionalInterface
    interface PasswordPrompt {
        /**
         * @param fileName             name of the file being opened, for display purposes
         * @param attempt              1-based attempt number (never more than {@link #MAX_ATTEMPTS})
         * @param previousAttemptFailed {@code true} if this isn't the first attempt and the
         *                             previous password was rejected
         * @return password characters to try next, or {@code null} to give up (surfaced from
         *         {@link #resolve} as {@link PasswordEntryCancelledException})
         */
        char[] getPassword(String fileName, int attempt, boolean previousAttemptFailed);
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

    /** Thrown when the {@link PasswordPrompt} returns {@code null} instead of a password. */
    static final class PasswordEntryCancelledException extends IOException {
    }

    /**
     * Runs on a background thread. Tries to open the PDF without a password
     * first; if OpenPDF signals that a password is required, repeatedly asks
     * {@code passwordPrompt} (up to {@link #MAX_ATTEMPTS} times) until it
     * opens, the prompt returns {@code null}
     * ({@link PasswordEntryCancelledException}), or the attempt limit is
     * reached ({@link IOException}).
     *
     * @param file               the PDF file to open
     * @param passwordPrompt     supplies a password to try for each attempt; see {@link PasswordPrompt}
     * @param applyKnownBugPatch whether to try {@link #patchOutOfRangePermissions} as
     *                           a fallback when the first, unmodified open attempt fails
     *                           with {@link BadPasswordException}. Wired to a checkbox in
     *                           {@link AbstractPdfViewerApp}'s shared top panel, checked
     *                           by default; pass {@code false} to open the file as-is
     *                           instead - e.g. to test whether a patched OpenPDF build no
     *                           longer needs this workaround at all.
     */
    static Resolved resolve(File file, PasswordPrompt passwordPrompt, boolean applyKnownBugPatch) throws IOException {
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

            boolean previousAttemptFailed = false;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                char[] password = passwordPrompt.getPassword(file.getName(), attempt, previousAttemptFailed);
                if (password == null) {
                    throw new PasswordEntryCancelledException();
                }
                byte[] bytes = toBytes(password);
                try {
                    return new Resolved(new PdfReader(originalBytes, bytes), bytes);
                } catch (BadPasswordException wrongPassword) {
                    previousAttemptFailed = true;
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

    private static byte[] toBytes(char[] chars) {
        byte[] bytes = new byte[chars.length];
        for (int i = 0; i < chars.length; i++) {
            bytes[i] = (byte) chars[i];
        }
        Arrays.fill(chars, '\0');
        return bytes;
    }
}
