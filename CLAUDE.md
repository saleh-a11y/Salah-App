# Salah Tracker – notes for Claude

## What's new banner (do this for every update people will notice)

After an update, the app shows a **What's new** banner once on the Today page. Its text comes from the
`NEWS` list in `www/index.html` (search for `const NEWS=`).

For every change the user or their family will notice, add an entry at the **top** of `NEWS`:

```js
{n:<previous top n + 1>,d:'<day Mon YYYY>',t:'<short title>',items:[
  '<b>Feature.</b> One or two plain sentences: what it does and where to find it.'
]},
```

- Plain, friendly words for non-technical family members. No code or file names.
- Behind-the-scenes fixes that nobody will notice don't need an entry.
- Several changes on the same day can go in one entry.
- New installs start "up to date" and only see entries added after they installed.

## Qur'ān reader

- `www/quran.html` runs inside an iframe of the main app. Native plugins must be reached through
  `parent.Capacitor`, not the iframe's own Capacitor.
- Page n of both PDFs (plain built-in, colour downloaded) = app page n, so the index, slider and bookmarks line up.

## Live from the masjid

- Streams come from LiveMasjid: `https://relay.livemasjid.com:8443/<stream>` (200 = on air, 404 = off air).
- Native side: `LiveService.java` (player, adhān-time waiting, background listening, alerts) and `LiveStream.java` (plugin).
  The background listener follows the **main masjid** (`S.boards[0]`).
