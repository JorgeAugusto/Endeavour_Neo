# Endeavour Neo

A desktop application shell in Swing, laid out like an IDE.

```
+- Menu -----------------------------------------------+
+- Toolbar --------------------------------------------+
| Navigator |  Editors (tabs)                          |
|  (tree)   |                                          |
|           +------------------------------------------+
|           |  Console                                 |
+-----------+------------------------------------------+
| Status bar                                           |
+------------------------------------------------------+
```

## Running

From an IDE: `br.com.jorge.reis.endeavourneo.Launcher`.
Pass `--light`, `--dark` or `--night` to pick a theme; the choice is remembered.

```
mvn compile
```

## Why Swing and not Eclipse RCP

The goal was the **structure and the look** of an IDE, with no need for
third-party plugins. Every piece of that structure has a direct Swing
equivalent:

| Eclipse piece | here |
|---|---|
| Dockable views | `JSplitPane` |
| Editors | `JTabbedPane` |
| Navigator | `JTree` |
| Console | `JTextArea` with standard output redirected |
| Status line | `JPanel` + `JProgressBar` |
| Commands | `Action` + `KeyStroke` |
| Jobs | `SwingWorker` |
| Preferences | `JTree` + `CardLayout` dialog |
| Perspectives | `java.util.prefs.Preferences` |

What Eclipse RCP charges dearly for — OSGi, Tycho, a target platform,
`MANIFEST.MF`, `plugin.xml`, `.product` and a native SWT binary per platform —
is the price of **extensibility**, which is not in play here. Without
third-party plugins, RCP is a tax with nothing in return.

The proof that Swing gets there: IntelliJ IDEA, DataGrip and Android Studio are
written in Swing.

## What is not here

**Real docking.** You cannot drag the console to the right edge or tear it off
into its own window, and there are no named perspectives. That is the expensive
part of RCP and also the least used. Worth adding when its absence hurts —
there are Swing docking libraries — and not before.

What does exist is the useful half: **window size and divider positions are
stored**, so the application reopens the way you left it.

## Themes

Three, under `File > Preferences...`:

| theme | for |
|---|---|
| Light | a lit room |
| Dark | same high contrast, dark background; an aesthetic preference |
| **Night** | amber instead of blue, and *lower* contrast; an unlit room |

Night is not "dark, but darker". Colours move from blue towards amber because
blue light is what most disturbs sleep, and contrast drops because pure white on
pure black in the dark produces a halo that tires the eye over a long session.
High contrast is a virtue by day and a defect at 3am.

The palette lives in `src/main/resources/themes/FlatDarkLaf.properties`. FlatLaf
derives dozens of colours from half a dozen base ones, so edit the bases and
everything stays coherent.

## Look and feel

[FlatLaf](https://www.formdev.com/flatlaf/) is the look and feel derived from
IntelliJ. `Appearance` loads it **by reflection**: without the jar the
application still starts, using the system look and feel, instead of dying with
`NoClassDefFoundError`.

## Translations

Standard `ResourceBundle`, no dependency:

```
src/main/resources/messages.properties          English (base, must stay complete)
src/main/resources/messages_pt_BR.properties    Brazilian Portuguese
```

The JVM picks by `Locale.getDefault()`. Adding a language means adding one file;
keys missing from a translation fall back to English, so a half-finished
translation still works.

A missing key renders as `!key.name!` rather than an empty string — a blank
label looks like a layout bug and gets hunted for hours, while `!action.exit!`
says exactly what is wrong. `MainWindowTest` walks the menu bar looking for that
marker, so forgetting a key in the bundle fails the build.

Mnemonics live in the bundle too, under `<key>.mnemonic`, because the underlined
letter differs per language: *File* wants F, *Arquivo* wants A.

## Layout

```
Launcher.java   the composition root: wires the layers and starts the window
platform/       Appearance, Theme, Messages, JobService, Progress
ui/shell/       MainWindow, Navigator, Console, StatusBar
ui/settings/    SettingsDialog, SettingsPage, AppearancePage
```

**Dependencies point inward.** `platform` never imports `ui` — services must be
usable with no screen at all, which is what lets the same code run from a
command-line tool. `LayerBoundaryTest` fails the build if that stops being true,
with no exemptions: the one class that legitimately touches every layer is
`Launcher`, and it sits at the root rather than inside a layer for exactly that
reason.

Adding a settings page means writing one `SettingsPage` and registering it in
`MainWindow.openPreferences`. The dialog itself never changes.

## Requirements

Java 17. Maven for the build. No runtime dependency other than FlatLaf, which is
optional.
