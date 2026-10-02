# PteronPDF for Android

Kotlin + Jetpack Compose port of the desktop app. Open in Android Studio (Ladybug+), let Gradle sync, run.

## Architecture
- `pdf/PdfEngine` – MuPDF (same engine family as PyMuPDF). ONE worker thread owns the document. The file is read
  on demand through a seekable stream over the file descriptor: never loaded into RAM, never copied to storage.
- `pdf/ReaderViewModel` – state, undo/redo, search, save. Bitmap LRU capped at 1/8 of the heap.
- `ui/PageList` – lazy page list; pinch = GPU scale while fingers move, one crisp re-render on release.
- `ui/MarkupOverlay` – pen/marker/highlighter, line/arrow/rect/circle, text, eraser. Stored in page coordinates.
- No storage permission (system picker), no icon library (hand-drawn icons), ABI filter arm64 + armv7.

## Parity with desktop
Done: open/recents/last page, lazy render, zoom, search (NFC+NFD), thumbnails, markup, undo/redo, save/save-a-copy,
11 themes + light/dark/system, welcome screen.
Not yet: move/resize existing shapes, rotate/delete page, tapered "writing pen", password PDFs, tiled rendering above ~2.5x.

## Verify first (written without compiling)
MuPDF Java signatures: `AndroidDrawDevice.drawPage(Page, Matrix)`, `PDFPage.deleteAnnotation`, `Page.search` (Quad[][]),
`doc.save` option string. Check against the fitz version in app/build.gradle.kts.
## License
MuPDF is AGPL (PyMuPDF is too). Closed-source/Play distribution needs an Artifex commercial license or an open-source release.

## Build the APK on GitHub
Push to `main` -> Actions tab -> "Build APK" -> open the run -> download the `PteronPDF-debug-apk` artifact.
(Debug-signed APK, installable directly. A Play Store release needs your own keystore.)
