# Standalone extract

`PasswordResolver.java` in this directory is a copy-paste-ready extract of
the same-named class from
[`src/main/java/dev/diegogarcia/openpdfpoc`](../src/main/java/dev/diegogarcia/openpdfpoc/PasswordResolver.java),
kept here specifically so it can be dropped into *other* projects that hit
the OpenPDF bug described in the main [README.md](../README.md#known-openpdf-quirk-out-of-range-p-permissions)
("Known OpenPDF quirk: out-of-range /P permissions").

As of this writing, that bug has no fix in an official OpenPDF release
(reproduces as far forward as `1.3.43`), so this file is the interim
workaround: it detects the bug and patches the affected bytes itself,
in-memory, before handing them to `PdfReader`.

## Using it

1. Copy `PasswordResolver.java` into your own project's source tree, in
   whatever package you like (add a `package ...;` line at the top).
2. Make sure your project already depends on OpenPDF (`1.3.x`) and
   BouncyCastle the same way this repo's `build.gradle` does - this file
   needs nothing else. It has no dependency on any other class from this
   repo, and no dependency on Swing or any other UI toolkit.
3. Call `PasswordResolver.resolve(file, passwordPrompt)`, where
   `passwordPrompt` is your own implementation of the
   `PasswordResolver.PasswordPrompt` functional interface - a single method
   that's asked for a password once per attempt (up to
   `PasswordResolver.MAX_ATTEMPTS` times) and returns `null` to give up.
   Implement it however fits your project: a Swing dialog, a web
   request/response round trip, a console prompt via `System.console()`, a
   password read from configuration, etc. If you don't need interactive
   prompting at all, lift out just
   `PasswordResolver.patchOutOfRangePermissions(byte[])` instead - it's a
   pure `byte[] -> byte[]` transform with no other dependency, and is the
   only part of this class that actually fixes the bug.

## License

The file carries its own header: a permissive, MIT-style notice that only
requires keeping that notice (and its credit to the original author,
Diego Garcia) attached to any copy you make. See the header itself for the
exact terms - it travels with the file, so there's nothing extra to do
beyond not deleting it.
