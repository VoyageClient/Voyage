# Misskey-style image pack ZIPs

Voyage imports and exports Matrix image packs in a ZIP format based on [Misskey's packed emoji format](https://misskey-hub.net/en/docs/for-admin/features/managing-emojis/). Voyage adds sticker usage and a pack icon. Its exports omit some fields in Misskey's documented format.

## Format

The ZIP contains image files and a UTF-8 `meta.json` at its root. For example, alongside `wave.png`, `dance.gif`, and `pack_icon.png`:

```json
{
  "metaVersion": 2,
  "exportedAt": "2026-10-01T12:00:00Z",
  "packAvatar": { "fileName": "pack_icon.png" },
  "emojis": [
    {
      "downloaded": true,
      "fileName": "wave.png",
      "emoji": { "name": "wave", "category": "my-pack", "aliases": [] }
    }
  ],
  "stickers": [
    {
      "downloaded": true,
      "fileName": "dance.gif",
      "sticker": { "name": "dance", "category": "my-pack", "aliases": [] }
    }
  ]
}
```

`fileName` identifies an image in the ZIP. `name` is its Matrix shortcode. Listing one file in both arrays makes it both an emoticon and a sticker. Voyage puts the pack name in `category`; categories do not group images within the pack. `packAvatar` points to the icon and may reuse an image listed in either array. Voyage writes `metaVersion` and `exportedAt` but does not check them on import.

## Import

- Each ZIP creates a new room pack. You can select several ZIPs at once.
- `meta.json` is optional. Images absent from its lists, or in a ZIP without metadata, become both emoticons and stickers. Their filenames supply their shortcodes. A separate image named by `packAvatar` becomes the icon alone.
- Voyage reads `emojis` first, then `stickers`, preserving list order. Other images follow in ZIP order. `fileName` can be a full ZIP path or a basename. An entry marked `downloaded: false` does not set the image's name or usage. If its file is in the ZIP, Voyage still imports it as an unlisted image. A missing `downloaded` field is accepted.
- One distinct nonempty `category` across the metadata becomes the pack name. Otherwise Voyage uses the ZIP filename without `.zip`, if available. Shortcodes allow ASCII letters, digits, `-`, and `_`; other characters become `_`. Names are limited to 100 characters, and collisions get numeric suffixes.
- Voyage accepts PNG, APNG, JPEG, GIF, WebP, and BMP files, including images in subdirectories. It uploads them to Matrix and may resize them to at most 1024 pixels on either side. A pack with only one usage stores that usage at pack level. Mixed usage uses the legacy Matrix image-pack event type to retain usage for each image.

## Export and Misskey compatibility

Voyage writes `meta.json`, all images it can download, and a separate icon file when needed. Each listed image gets `downloaded: true`, its filename and shortcode, the pack name as `category` when present, and an empty `aliases` array. Failed downloads are omitted and reported in the app. Export fails when no images in a nonempty pack can be downloaded.

Misskey's documented emoji entries contain additional database fields such as IDs and URLs. Voyage ignores those fields and aliases on import. Misskey's documented format has no `stickers` or `packAvatar` field, so a Misskey import may lose sticker usage and the pack icon.
