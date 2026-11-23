# Glimkey themes

Settings > Appearance > Theme. A theme changes geometry, palette, keycap material, motion and font, not just colours.

| Theme | Shell | Keycap | Press | Font |
|---|---|---|---|---|
| Liquid Glass | frosted, blur / AGSL | flat tonal, 10dp | scale + corner spring | Google Sans Rounded |
| Paper | lined page, red margin | index card, hard shadow, ink border | card lies flat | serif |
| Terminal | black, scanlines, green edge | outlined block | inverts to solid phosphor | monospace |
| Mechanical | metal case, bevel | dished cap in a skirt | cap sinks into the skirt | sans-serif-medium |
| Clay | soft flat shell | neumorphic: light + dark shadow pair | key sinks inward (inner shadow) | Google Sans Rounded |
| Pixel | dark indigo, dither | notched 8-bit block on a hard slab | block drops onto its slab, fills yellow | monospace bold (or `assets/fonts/pixel.ttf`) |
| Nokia LCD | green LCD, pixel grid | ink outline, LCD ghost shadow | fills solid ink | sans-serif-condensed bold |

Each of the three newest themes has its own typing sound (`assets/sounds/{neu,pixel,lcd}`). Settings > Feedback > Sound style defaults to
"Ikuti tema" (follow theme); the older themes keep the iOS pack. Pick any other style to override.

Code map: `KbTheme.kt` (themes + palettes), `KbTokens` (resolves everything from the theme), `KeyBodyDrawable` (keycap skins),
`GlassSurfaceDrawable.drawThemed` (shell / popups), `ThemePicker.kt` (settings miniatures).
Non-glass themes ignore Material You, accent, blur and lens settings (hidden in Settings). Key shape / spacing are stored per theme.
To add a theme: add an enum entry, a `Palette`, a font overlay and (optionally) a new `KeySkin`.

## Tap effects (separate from themes)

Settings > Feedback > Tap effect. This is its own setting, so any effect works on any theme (like Samsung Keys Cafe):
Off, Ripple, Sparkle, Hearts, Bubbles, Confetti, Petals. "Tap effect color" picks Auto (each effect's own colours),
Theme accent, or Rainbow. Code: `TapFx.kt` (`TapEffect` + `TapFxView`, an untouchable overlay above the keys); `KeyView` calls
`tapFx.burst()` on press, after the input, haptic and sound. To add one: add an enum entry and a branch in `burst()` / `onDraw()`.

**Back effect** (Settings > Feedback > Back effect): drawn behind the keys, on the shell, so it shows through the gaps and around the keycaps.
Off, Press glow (the built-in glass glow, moved here from Appearance; still the default on Liquid Glass, Android 13+), Paint splat (irregular blob + droplets), Glow bloom, Ink spread. It shares "Tap effect color" and can be combined with a front tap effect.
Code: `BackEffect` + `TapFxBackView` in `TapFx.kt` (child 0 of the host, under the key rows, clipped to the shell shape).
