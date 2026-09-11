# openpdf-poc

A minimal Java Swing application that opens a PDF file and displays its
content, prompting for a password when the PDF is protected for viewing.
Built as a proof of concept for handling OpenPDF's single-password model,
where the same API accepts either the document's **owner** (edit/change)
password or its **user** (open/view) password.

It ships two interchangeable viewer engines (see [Viewer
engines](#viewer-engines)): a pure-OpenPDF text view, and a PDFBox-rendered
page-image view that still uses OpenPDF for the password handling. Running
the app opens a small launcher window with a button for each engine.

## Requirements

- JDK 8 to run the built application (the Gradle toolchain will also use a
  local JDK 8 to compile/target bytecode; see [Toolchain](#toolchain) below).
- Gradle wrapper (bundled) — no local Gradle install required.

## Build & run

```bash
./gradlew run
```

or build a runnable distribution:

```bash
./gradlew installDist
build/install/openpdf-poc/bin/openpdf-poc
```

Either way, a launcher window opens first with two buttons - **Pure OpenPDF
(text)** and **PDFBox (render)** - each opening its own viewer window. The
launcher stays open after a viewer window is closed, so you can open another
(the same engine again, for a different file, or the other engine) without
restarting the app; closing the launcher itself quits the application.

## Viewer engines

| | Pure OpenPDF (text) | PDFBox (render) |
|---|---|---|
| Class | `OpenPdfTextViewerApp` | `PdfBoxRenderViewerApp` |
| Password check | OpenPDF (`PdfReader`, via `PasswordResolver`) | same — OpenPDF (`PdfReader`, via `PasswordResolver`) |
| Page display | extracted text (`PdfTextExtractor`) | rasterized page image (PDFBox `PDFRenderer`) |
| Extra dependency | none | Apache PDFBox |

Both engines share the same window chrome (`AbstractPdfViewerApp`): the open
button, remembered last-used directory, password dialog, and page
navigation. Only how a page is opened and drawn differs, in
`OpenPdfTextViewerApp` and `PdfBoxRenderViewerApp` respectively. Each viewer
window closes independently of the launcher (`LauncherWindow`) that opened
it.

Password handling is deliberately centralized in `PasswordResolver`, which
both engines call: it opens the file with OpenPDF's `PdfReader`, prompting
for a password (up to 5 attempts) if one is required, since that's the
behavior this whole POC exists to demonstrate. The PDFBox engine calls it
exactly the same way the text engine does — it just also hands the password
that OpenPDF validated to PDFBox's own loader afterwards, to actually
rasterize the page.

## How it works

1. Pick an engine on the launcher window (**Pure OpenPDF (text)** or
   **PDFBox (render)**); its viewer window opens.
2. Click **Open PDF...** and pick a `.pdf` file.
3. The app tries to open it with OpenPDF's `PdfReader`.
4. If the PDF requires a password to view, `PdfReader` throws
   `BadPasswordException` and the app prompts for a password (up to 5
   attempts). Entering either the correct **user** password or the correct
   **owner** password succeeds, since OpenPDF does not distinguish between
   the two at the API level — whichever one decrypts the document is
   accepted.
5. Once open, the document is displayed page by page:
   - **Pure OpenPDF (text)** — OpenPDF has no page rasterizer (it's a PDF
     generation/manipulation library, not a rendering engine), so "viewing"
     a page means showing that page's extracted text via `PdfTextExtractor`.
     A purely graphical page (e.g. a scanned image with no text layer) shows
     a placeholder message instead of a blank page.
   - **PDFBox (render)** — the same file (and password, if any) is reopened
     with Apache PDFBox's `PDDocument`, and each page is rasterized to an
     image via `PDFRenderer` for a real, pixel-accurate view.

## Sample PDFs

[`sample-pdfs/`](sample-pdfs/) has three files to exercise the password
handling described above:

| File | Owner (edit/change) password | User (open/view) password | What to expect |
|---|---|---|---|
| `no-password.pdf` | none | none | Opens immediately, no prompt. |
| `owner-password.pdf` | `password` | none | Opens immediately, no prompt - an owner-only password protects *editing*, not *viewing*, so `PdfReader` never asks for one. The status bar still reports it as encrypted. |
| `open-password.pdf` | `password` | `senha` | Prompts for a password. Both `senha` (the user password) and `password` (the owner password) unlock it - this is the "either password works" behavior this whole POC exists to demonstrate. |

## Known display quirk (and its workaround)

On some JDK 8 + Aqua look-and-feel + macOS combinations, a Swing text
component whose text mixes a "smart" typographic character (curly quotes,
en/em dashes, an ellipsis, a bullet, ...) with plain Latin letters can
silently drop random neighboring letters from that line — the special
character itself renders fine, but ordinary letters near it vanish. This
was reproducible in this app's own UI labels (e.g. `"Open PDF…"` rendering
as `"Open  DF..."`, missing the P) purely from the `…` character, and is a
platform/JDK font-fallback bug, not anything specific to PDF content.

Two things address it:

- The app's own labels/buttons were changed to plain ASCII (e.g. `"Open
  PDF..."`, `"< Previous"` / `"Next >"` instead of `"Open PDF…"` /
  `"◀ Previous"` / `"Next ▶"`).
- Extracted PDF page text is normalized before display (see
  `normalizeForDisplay` in `OpenPdfTextViewerApp`), replacing common
  smart-typography characters with their ASCII equivalents, since real-world
  PDFs (anything exported from Word/Office in particular) are full of them.
  This only applies to the Pure OpenPDF (text) engine — the PDFBox engine
  draws actual page pixels, so it isn't affected by this bug at all.

This is a compatibility workaround, not a design preference: it does not
help with genuinely non-Latin scripts (CJK, Arabic, etc.), which can still
hit the same underlying bug on an affected environment.

## Known OpenPDF quirk: out-of-range /P permissions

Some PDFs open with no prompt at all in other readers (e.g. macOS Preview)
but get flagged by this app as password-protected, and no password -
correct or not - ever unlocks them. This happens when the PDF's
`/Encrypt` dictionary writes its `/P` (permissions) entry as the equivalent
*unsigned* 32-bit decimal (e.g. `4294965956`) instead of the signed decimal
most PDF writers use for the same bit pattern (`-1340`) - both are
spec-legal, since they encode the identical 4-byte value.

OpenPDF 1.3.x stores every PDF number as a Java `double`
(`com.lowagie.text.pdf.PdfNumber`), and `PdfNumber#intValue()` narrows it
with a plain `(int)` cast. Java's narrowing double-to-int conversion
*saturates* rather than wraps for a value outside the `int` range, so a `/P`
value like `4294965956` is misread as `Integer.MAX_VALUE` instead of
`-1340`. That corrupts the encryption key OpenPDF derives, so its own
recomputed `/U` check fails for every password it's given - including a
correct, empty one - and `PdfReader` reports `BadPasswordException` for a
document that isn't actually password-protected. (This isn't fixed in newer
OpenPDF releases either - the same saturating cast is still present as of
`1.3.43`.)

`PasswordResolver` works around this: before treating a `BadPasswordException`
as a real password prompt, it scans the plaintext `/Encrypt` dictionary bytes
(never encrypted, so safe to inspect before any password is known) for an
out-of-range `/P` value near a `/Filter/Standard` marker, and rewrites it to
its correctly signed form before retrying. Every other file is returned
untouched - this only ever activates as a fallback after the first,
unmodified open attempt fails.

A minimal PDF fixed up this way can also have no `/Contents` entry on a
page at all (spec-legal - it just means the page is empty), which trips a
*second*, unrelated OpenPDF bug: `PdfTextExtractor` reads `/Contents` with
no null check and throws an uncaught `NullPointerException` (not
`IOException`) instead of returning empty text. `OpenPdfTextViewerApp`
catches that specifically and shows the same "no extractable text" message
used for other content-free pages, rather than letting it surface as a raw,
message-less "Unexpected error: null". The PDFBox render engine isn't
affected - it renders an empty page as a blank image with no error.

## Toolchain

`build.gradle` pins the Java toolchain to language version 8
(`JavaLanguageVersion.of(8)`), so Gradle compiles and runs the app with a
JDK 8 it locates automatically (e.g. one installed via SDKMAN!, asdf, or
Gradle's own toolchain provisioning) regardless of which JDK launched
Gradle itself.

The Gradle wrapper itself is pinned to **Gradle 8.14.3** in
`gradle/wrapper/gradle-wrapper.properties`, not a newer 9.x release: Gradle
9 raised the *minimum JVM Gradle itself runs on* to 17+, so it won't launch
at all on a machine whose only available JDK is 8, regardless of what the
toolchain block above targets. `8.14.3` is the newest Gradle release still
able to run on a JDK 8 daemon. This is unrelated to the JDK 8 *toolchain*
requirement above - that always applied, independent of which Gradle
version is doing the compiling.

## Dependency notes

- **OpenPDF `1.3.36`** — the `1.3.x` line is OpenPDF's Java 8-compatible
  branch. `1.3.36` is the last release before `1.3.37`, which shipped a
  broken pom pulling in a Java 9+ baseline (see
  [LibrePDF/OpenPDF#1033](https://github.com/LibrePDF/OpenPDF/issues/1033)).
  Versions `2.x` require Java 11+ and are not usable here.
- **BouncyCastle (`bcprov-jdk18on` / `bcpkix-jdk18on`)** — OpenPDF marks
  these as *optional* in its own pom, but they are required at runtime to
  decrypt password-protected PDFs; without them, opening an encrypted PDF
  throws `NoClassDefFoundError` on an `org.bouncycastle.*` class. They are
  declared explicitly here as `runtimeOnly` dependencies for that reason.
- **Apache PDFBox `2.0.29`** — used only by the `pdfbox` rendering engine, to
  rasterize pages to images (something OpenPDF cannot do at all). `2.0.x` is
  PDFBox's last Java 8-compatible branch; `3.x` requires Java 17+.
