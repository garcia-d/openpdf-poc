# Project purpose

`openpdf-poc` is a proof-of-concept Swing application demonstrating how to
handle password-protected PDFs with **OpenPDF**, a Java library positioned
as a replacement for iText. Unlike some PDF libraries, OpenPDF exposes a
single password parameter that transparently accepts either of a PDF's two
distinct passwords, without the caller having to say which one it's
supplying:

- **Owner (edit/change) password** — restricts editing/permissions only; a
  PDF protected only by this password still opens for viewing without a
  prompt.
- **User (open/view) password** — required just to open/view the document
  at all.

`PdfReader` accepts whichever password is supplied, as long as it decrypts
the document. This project exists to demonstrate detecting when a password
is required, prompting the user for it, and successfully opening the
document with either password.

# Current architecture

The app opens a launcher window with two buttons, each launching an
independent "viewer engine" window (`LauncherWindow`, `Main`):

- **`OpenPdfTextViewerApp`** — pure OpenPDF. OpenPDF has no page
  rasterizer (it's a generation/manipulation library, not a rendering
  engine), so it displays each page's extracted text (`PdfTextExtractor`).
- **`PdfBoxRenderViewerApp`** — renders actual page images via Apache
  PDFBox's `PDFRenderer`. Password detection/validation is still done with
  OpenPDF first (see below); PDFBox is used only to rasterize pages.

Shared code:

- **`AbstractPdfViewerApp`** — common window chrome: open button, file
  chooser (remembering the last directory used), password-prompt wiring,
  page navigation, status bar. Subclasses implement `buildContentPanel`,
  `openDocument`, `renderPage`, `closeDocument`, and must call
  `initializeUi()` as the last statement of their constructor (calling it
  any earlier trips a constructor-ordering bug — see the class Javadoc).
- **`PasswordResolver`** — the *only* class that calls OpenPDF's
  `PdfReader` to detect/validate a password (prompting via a modal dialog,
  up to 5 attempts). Both viewer engines call this the same way.

Each viewer window is independent of the launcher and of each other:
closing one only disposes that window (`DISPOSE_ON_CLOSE`); the launcher —
and the JVM — keeps running so the user can open another file, or the same
file with the other engine. Closing the launcher itself quits the app
(`EXIT_ON_CLOSE`).

# Constraints (do not violate without asking)

- Pure Java Swing only — no other UI toolkit.
- JDK version must be 8 (see `build.gradle`'s `java.toolchain` block); don't
  use language features or APIs beyond Java 8.
- Build tool is Gradle (latest), via the Gradle wrapper, using the Groovy
  DSL (`build.gradle`, not `build.gradle.kts`).
- **OpenPDF** (`com.github.librepdf:openpdf`, pinned to `1.3.36` — the last
  release on the `1.3.x` branch, which is the last Java 8-compatible one;
  `1.3.37`+ ships a pom requiring Java 9+, and `2.x` requires Java 11+) is
  mandatory for **all** password detection/validation, in both viewer
  engines — see `PasswordResolver`. This is the whole point of the POC:
  don't let the PDFBox engine bypass it (e.g. by asking PDFBox to
  guess/validate the password itself instead of reusing OpenPDF's result).
- **Apache PDFBox** (pinned to `2.0.29` — the last Java 8-compatible
  release; `3.x` requires Java 17+) is permitted, but **only** as the
  rendering backend for `PdfBoxRenderViewerApp`. Don't introduce other
  PDF-handling libraries without asking first.
- BouncyCastle (`bcprov-jdk18on` / `bcpkix-jdk18on`) is a required
  *runtime* dependency of OpenPDF for decrypting password-protected PDFs
  (OpenPDF marks it optional in its own pom, but throws
  `NoClassDefFoundError` without it) — keep it declared explicitly in
  `build.gradle`.

# Known platform quirk: keep UI text ASCII-only

On this JDK 8 + Aqua look-and-feel + macOS combination, a Swing text
component whose text mixes a "smart" typographic Unicode character (curly
quotes, en/em dashes, an ellipsis `…`, a bullet `•`, decorative arrows like
`◀`/`▶`, ...) with plain Latin letters can silently drop random neighboring
letters from that same line of text — confirmed by isolating it down to
individual characters (e.g. a lone `…` in `"Open PDF…"` was enough to blank
out the `P`, reproducibly, across repeated runs). This is a platform/JDK
font-fallback bug, not anything specific to PDF content or to this app's
code, and it does not require any particular PDF to trigger — the app's own
hardcoded button labels were enough.

Two rules follow from this:

1. **Never use decorative/typographic Unicode characters in this app's own
   UI strings** (button labels, status messages, dialog text, etc.) — use
   plain ASCII equivalents instead (e.g. `"..."` not `"…"`, `"< Previous"` /
   `"Next >"` not `"◀ Previous"` / `"Next ▶"`, `" - "` not `" • "`).
2. Text extracted *from* a PDF (in `OpenPdfTextViewerApp` only —
   `PdfBoxRenderViewerApp` draws page pixels, so it's unaffected) is
   normalized before display (`normalizeForDisplay`), replacing common
   smart-typography characters with ASCII equivalents, since real-world
   PDFs (anything exported from Word/Office in particular) are full of
   them. This is a compatibility workaround, not a design preference — it
   does not help with genuinely non-Latin scripts (CJK, Arabic, etc.),
   which can still hit the same underlying bug.

Keep following both rules even if this is later built/run on a JDK/OS
combination where the bug doesn't reproduce — it's cheap portability
insurance, not something to re-verify per environment.

# Sample PDFs for manual testing

`sample-pdfs/` has three files exercising the two password types (see the
"Sample PDFs" section of `README.md` for the same table, kept in sync with
this one):

| File | Owner password | User (open) password |
|---|---|---|
| `no-password.pdf` | none | none |
| `owner-password.pdf` | `password` | none |
| `open-password.pdf` | `password` | `senha` |

`open-password.pdf` is the interesting one: both `senha` (the user
password) and `password` (the owner password) should successfully open it
— that's OpenPDF's single-password behavior, live.

# Build & run

```bash
./gradlew run
```

See `README.md` for full build/run instructions, the engine comparison
table, and more architectural detail.
