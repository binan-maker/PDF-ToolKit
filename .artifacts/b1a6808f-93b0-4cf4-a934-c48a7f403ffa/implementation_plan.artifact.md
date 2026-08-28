# Fix PDF Viewer Crashes and Gesture Issues

Address the recent crashes and horizontal panning regression in the PDF viewer components.

## Proposed Changes

### [PdfViewerViewModel](file:///D:/Pdf_Tools/app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerViewModel.kt)

#### [MODIFY] [PdfViewerViewModel.kt](file:///D:/Pdf_Tools/app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerViewModel.kt)

- Rebalance `bitmapCache` limits to be compatible with `MAX_RENDER_PIXELS`.
- Replace manual `OutOfMemoryError` throws with safer logic.

### [PdfViewerScreen](file:///D:/Pdf_Tools/app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt)

#### [MODIFY] [PdfViewerScreen.kt](file:///D:/Pdf_Tools/app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt)

- Fix `coerceIn` crash in the text selection menu `offset` block.
- Restore horizontal panning by adding `panChange.x` to the gesture logic.

## Verification Plan

### Manual Verification
- Open a PDF and perform a long-press to select text; verify the selection menu appears without crashing.
- Zoom into a page and verify that panning works both vertically AND horizontally.
- Verify that scrolling between large pages is smooth (indicating cache hits).
