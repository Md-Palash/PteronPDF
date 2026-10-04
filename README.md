# PteronPDF for Android

Kotlin + Jetpack Compose port of the desktop app. Open in Android Studio (Ladybug+), let Gradle sync, run.

## Architecture
- `pdf/PdfEngine` – MuPDF. ONE worker thread owns the document; the file is read on demand through a seekable stream.
  Markup made in a session is written into the PDF only when you save.
- `pdf/Markup`, `pdf/Geom` – markup model (page coordinates, widths/sizes in PDF points) and the geometry that makes
  every mark selectable, movable and resizable (hit-testing, handles, move).
- `pdf/ReaderViewModel` – state, undo/redo (add / remove / edit), search, save. Bitmap LRU capped at 1/8 of the heap.
- `ui/PageList` – lazy page list; pinch = GPU scale while fingers move, one crisp re-render on release.
- `ui/MarkupOverlay` – paints all markup as vectors and handles pen, text highlight, line, curve, arrow, square, circle,
  comments, eraser and select/move/resize.
- `ui/ReaderScreen` – fixed top bar (search, page n/N, menu), small edit button, slide-up editing card.
- `ui/SettingsScreen`, `ui/Cards` – grouped settings cards (Appearance / Reading / About).
- `theme/Fonts` – fonts are looked up by file name in `res/font` (`<name>_regular`, `<name>_bold`); only fonts that
  exist are offered. `tools/fetch_fonts.py` fetches and trims the Google ones in CI.
- No storage permission (system picker), no icon library (hand-drawn icons), per-ABI APKs.

## Fonts
Bundled: Inter, Outfit, Lato. CI fetches Playfair, Cinzel, Roboto and Playwrite US (Latin only, ~25 KB per file).
Aptos is a Microsoft font with its own licence and is not on Google Fonts: add `aptos_regular.ttf` / `aptos_bold.ttf`
to `res/font` yourself and it shows up in the list.

## Verify first (written without compiling)
MuPDF Java signatures: `AndroidDrawDevice.drawPage(Page, Matrix)`, `PDFPage.deleteAnnotation`, `Page.search` (Quad[][]),
`Page.toStructuredText()` + `StructuredText.highlight(Point, Point)`, `PDFAnnotation.setQuadPoints(Quad[])`,
`doc.save` option string. Check against the fitz version in app/build.gradle.kts.

## License
MuPDF is AGPL (PyMuPDF is too). Closed-source/Play distribution needs an Artifex commercial license or an open-source release.

## Build the APK on GitHub
Push to `main` -> Actions tab -> "Build APK" -> open the run -> download the `PteronPDF-debug` artifact.
(Debug-signed APK, installable directly. A Play Store release needs your own keystore.)
