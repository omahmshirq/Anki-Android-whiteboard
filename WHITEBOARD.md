# Persistent whiteboard — personal AnkiDroid build

This fork adds editable, per-card whiteboard drafts to the **new study screen**.
Local drawings are saved after each completed stroke, eraser gesture, clear, undo
or redo. Leaving a deck or closing the reviewer does not intentionally discard them.

## Get the Android app

1. Open this repository's **Actions** tab. If GitHub says workflows are disabled,
   click **I understand my workflows, go ahead and enable them**.
2. Select the **Whiteboard APK** workflow.
3. Click **Run workflow**, select the **feature/persistent-whiteboard** branch,
   and run it. The workflow runs focused tests before building.
4. Open the successful run and download **AnkiDroid-Whiteboard-APK** under
   **Artifacts**. Extract the ZIP and install its universal APK on Android.

The custom build is named **AnkiDroid Whiteboard**, with application ID
**com.ichi2.anki.whiteboard**. It installs alongside the official application and
uses the repository's existing test signing key for consistent updates.
It starts with its own app data: sign in to AnkiWeb and download your collection,
or import an exported collection with media.

This is a test build based on **2.26.0alpha5**, not an official AnkiDroid release.
A green workflow confirms the listed automated checks; a physical-device check
is still needed for touch input and actual AnkiWeb uploads/downloads.

## Use it

1. Enable **Settings → New Study Screen → Enable**.
2. Open a deck and enable its **Whiteboard**.
3. Draw normally. Completed edits are saved on this device automatically.
4. Open the whiteboard toolbar's overflow menu and select **Save and sync drawings…**.
5. Choose a field displayed on the answer, such as **Back**, **Extra**, or your
   MCQ note type's explanation field. Confirm **Enable autosave**.
6. Run normal Anki sync, including media, to upload the drawing.

Field selection is remembered **per note type and per device**. Once configured,
subsequent drawings on cards of that note type update the selected field
automatically. Existing field contents are preserved. If you choose a field not
included in a card template, its image will not appear in that template.

On another device running this fork, download the collection **and media**.
Opening the card restores editable strokes. Choose the sync field on that device
if you also want its later edits uploaded. Other Anki clients display the saved SVG
image wherever the selected field is displayed, but do not gain this native editor.

**Stop syncing this note type** keeps future changes local. It does not delete
previously synced attachments.

## Saving details and limits

- Draft keys include the collection's media path, note GUID and card ordinal,
  keeping sibling cards and different collections separate.
  Reordering or changing card templates can change ordinal associations.
- Drafts are atomic files in this app's private files directory. They are not a
  substitute for a synced note attachment and are lost when this app's data is cleared.
- Each synced drawing is one ordinary SVG media file. Its metadata includes
  serialized brush/eraser strokes, so media checking can see the reference and
  syncing preserves the editable document.
- Stroke geometry is sampled at approximately one-pixel spacing. A reload can
  therefore make subpixel curve changes.
- Drawings scale uniformly to fit the viewport. Existing geometry is transformed
  back to its document coordinates before saving to avoid repeated rotation shrinkage.
  Strokes are not anchored to individual words; scrolling or changing a card layout
  can change their alignment with the text underneath.
- Completing a clear operation removes this card's generated attachment on the
  next successful save. Whiteboard Undo can restore it while the session remains open.
- Existing media files are never overwritten or automatically deleted. Old
  revisions may later appear in Anki's unused-media check.
- Consecutive autosaves for one drawing are grouped into a normal note-edit Undo
  entry, preserving earlier review Undo history. The reviewer Undo action may first
  revert the saved-image group. Use the whiteboard toolbar for stroke Undo/Redo;
  its stroke history starts fresh on card change.
- If a different saved image is already on the note, autosave refuses to overwrite
  it. The local draft remains. **Save and sync drawings…** explicitly authorizes
  replacing that other version; there is no automatic merge between devices.
- Images belong to note fields. A template that displays that field on sibling
  cards can also display their attached images, while native draft restoration
  still selects only the current card's drawing.
- Limits: 5,000 strokes, 200,000 sampled points and 8 MiB per document/media file.
  Save errors are shown; the current drawing should be kept open until resolved.
- This change targets the new reviewer. The legacy reviewer's whiteboard is unchanged.

## Checks

The workflow runs the whiteboard unit tests and reviewer gesture regressions,
including new cases for:

- leaving and reopening the reviewer;
- switching cards and undoing an answer;
- retaining separate sibling-card drawings;
- editable SVG round trips, erasers, scaling and malformed input;
- local draft reopening and edits arriving during a sync write;
- preserving note text and existing media;
- restoring from a fresh local store, media-reference detection and sync conflicts.
- preserving earlier Undo entries without merging unrelated collection operations.

The implementation is AI-assisted and intended for this personal fork.
