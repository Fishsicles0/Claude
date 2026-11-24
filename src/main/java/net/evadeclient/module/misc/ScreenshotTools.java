package net.evadeclient.module.misc;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.Notifications;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * [Copy] [Folder] [Delete] buttons after vanilla's "Saved screenshot as ..." chat message. The buttons run a
 * client-only command ({@code /evadeshot}) that never reaches the server, and only ever touch a bare file name inside
 * the game's screenshots folder. Delete asks for a second click.
 */
public class ScreenshotTools extends Module {
    private final BooleanSetting copyButton = add(new BooleanSetting("Copy Button", true)
            .describe("A [Copy] button that puts the image on your clipboard, ready to paste."));
    private final BooleanSetting folderButton = add(new BooleanSetting("Folder Button", true)
            .describe("A [Folder] button that opens the screenshots folder with the file selected."));
    private final BooleanSetting deleteButton = add(new BooleanSetting("Delete Button", true)
            .describe("A [Delete] button. Click it twice to delete the screenshot."));
    private final BooleanSetting autoCopy = add(new BooleanSetting("Auto Copy", false)
            .describe("Copies every new screenshot to the clipboard as soon as it is saved."));
    /** Opens the gallery (a read-only row: never saved). */
    private final net.evadeclient.setting.StatusSetting gallery = add((net.evadeclient.setting.StatusSetting)
            new net.evadeclient.setting.StatusSetting("Screenshot Gallery", () -> "Open", "click: open")
                    .onClick(() -> net.evadeclient.gui.ScreenshotGalleryScreen.openFromCurrent())
                    .describe("Browse this game folder's screenshots: view, copy, rename and delete them (also .shots)."));

    /** The client-side command the buttons run. Fabric runs it locally; it is never sent to the server. */
    public static final String COMMAND = "evadeshot";
    private static final long DELETE_CONFIRM_MS = 5000;
    /** A bare PNG file name: no folders, drive letters, wildcards or control characters. */
    private static final Pattern FILE_NAME = Pattern.compile("^[^/\\\\:*?\"<>|\\p{Cntrl}]{1,120}\\.png$", Pattern.CASE_INSENSITIVE);

    private static String pendingDelete;
    private static long pendingDeleteAt;

    public ScreenshotTools() {
        super("Screenshot Tools", "Copy, folder and delete buttons on the screenshot message.", Category.MISC);
        setEnabled(true);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommandManager.literal(COMMAND)
                        .then(action("copy"))
                        .then(action("folder"))
                        .then(action("delete"))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> action(String name) {
        return ClientCommandManager.literal(name)
                .then(ClientCommandManager.argument("file", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            run(name, StringArgumentType.getString(ctx, "file"));
                            return 1;
                        }));
    }

    @Override public List<String> commandAliases() { return List.of("shots", "screenshots"); }

    @Override public List<String> commandHelp() { return List.of("§d.shots§7 - open the screenshot gallery"); }

    /** {@code .shots}: works with the module off too (it only opens the gallery). */
    @Override
    public void command(String[] args) {
        mc.send(() -> net.evadeclient.gui.ScreenshotGalleryScreen.open(null)); // once chat has closed
    }

    private static ScreenshotTools active() {
        if (EvadeClient.modules == null) return null;
        ScreenshotTools st = EvadeClient.modules.get(ScreenshotTools.class);
        return st != null && st.isEnabled() ? st : null;
    }

    // ------------------------------------------------------------------ the chat message

    /**
     * From the screenshot mixin: wraps the receiver of vanilla's screenshot message so the success message gets the
     * buttons. Unchanged while the module is off.
     */
    public static Consumer<Text> wrap(Consumer<Text> receiver) {
        ScreenshotTools st = active();
        if (st == null || receiver == null) return receiver;
        return text -> {
            Text out = text;
            try {
                out = st.decorate(text);
            } catch (RuntimeException e) {
                EvadeClient.LOG.warn("Screenshot Tools couldn't add its buttons", e);
            }
            receiver.accept(out);
        };
    }

    /** The success message with the buttons after it; any other message unchanged. Runs on the IO worker. */
    Text decorate(Text text) {
        File file = savedFile(text);
        if (file == null) return text;
        if (autoCopy.get()) copy(file);
        String name = file.getName();
        if (!FILE_NAME.matcher(name).matches()) return text; // a name the command wouldn't accept: no buttons
        MutableText out = text.copy();
        if (copyButton.get()) out.append(" ").append(button("Copy", Formatting.AQUA, "copy", name, "Copy the image to the clipboard"));
        if (folderButton.get()) out.append(" ").append(button("Folder", Formatting.YELLOW, "folder", name, "Open the screenshots folder"));
        if (deleteButton.get()) out.append(" ").append(button("Delete", Formatting.RED, "delete", name, "Delete this screenshot (click twice)"));
        return out;
    }

    /** The file named in a "Saved screenshot as ..." message (its click-to-open link), or null for anything else. */
    public static File savedFile(Text text) {
        if (text == null || !(text.getContent() instanceof TranslatableTextContent t) || !"screenshot.success".equals(t.getKey())) return null;
        for (Object arg : t.getArgs()) {
            if (arg instanceof Text a && a.getStyle().getClickEvent() instanceof ClickEvent.OpenFile open) return open.file();
        }
        return null;
    }

    private static Text button(String label, Formatting color, String action, String file, String hint) {
        Style style = Style.EMPTY.withColor(color)
                .withClickEvent(new ClickEvent.RunCommand("/" + COMMAND + " " + action + " " + file))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(hint)));
        return Text.literal("[" + label + "]").setStyle(style);
    }

    // ------------------------------------------------------------------ the command

    /** Runs a button's action for {@code fileName} (a bare name inside the screenshots folder). Client thread. */
    public static void run(String action, String fileName) {
        File file = resolve(fileName);
        if (file == null) {
            CommandManager.print("§7That isn't a screenshot in your screenshots folder.");
            return;
        }
        switch (action) {
            case "copy" -> {
                if (!file.isFile()) CommandManager.print("§7" + file.getName() + " no longer exists.");
                else copy(file);
            }
            case "folder" -> openFolder(file);
            case "delete" -> delete(file);
            default -> CommandManager.print("§7Unknown screenshot action: " + action);
        }
    }

    /** The screenshots folder the game saves into. */
    public static Path folder() {
        return new File(EvadeClient.mc().runDirectory, "screenshots").toPath().toAbsolutePath().normalize();
    }

    /** {@code fileName} inside the screenshots folder, or null unless it is a bare PNG file name that stays inside it. */
    public static File resolve(String fileName) {
        if (fileName == null) return null;
        String name = fileName.strip();
        if (!FILE_NAME.matcher(name).matches() || name.startsWith(".")) return null;
        Path dir = folder();
        Path p = dir.resolve(name).normalize();
        return dir.equals(p.getParent()) ? p.toFile() : null;
    }

    private static void delete(File file) {
        String name = file.getName();
        long now = System.currentTimeMillis();
        if (!name.equals(pendingDelete) || now - pendingDeleteAt > DELETE_CONFIRM_MS) {
            pendingDelete = name;
            pendingDeleteAt = now;
            CommandManager.print("§7Click §c[Delete]§7 again within 5 seconds to delete §f" + name);
            return;
        }
        pendingDelete = null;
        Util.getIoWorkerExecutor().execute(() -> {
            String result;
            try {
                result = Files.deleteIfExists(file.toPath()) ? "§7Deleted §f" + name : "§7" + name + " was already gone.";
            } catch (IOException | SecurityException e) {
                result = "§7Couldn't delete " + name + ": " + e.getMessage();
            }
            String msg = result;
            EvadeClient.mc().execute(() -> CommandManager.print(msg));
        });
    }

    /** Opens the screenshots folder, with the file selected where the system's file manager can do that. */
    public static void openFolder(File file) {
        Path dir = folder();
        Util.getIoWorkerExecutor().execute(() -> {
            try {
                Files.createDirectories(dir);
                String path = file.getAbsolutePath();
                boolean exists = file.isFile();
                switch (Util.getOperatingSystem()) {
                    case WINDOWS -> {
                        if (exists) new ProcessBuilder("explorer.exe", "/select,", path).start();
                        else Util.getOperatingSystem().open(dir.toFile());
                    }
                    case OSX -> {
                        if (exists) new ProcessBuilder("open", "-R", path).start();
                        else Util.getOperatingSystem().open(dir.toFile());
                    }
                    default -> Util.getOperatingSystem().open(dir.toFile());
                }
            } catch (IOException | RuntimeException e) {
                EvadeClient.mc().execute(() -> CommandManager.print("§7Couldn't open the screenshots folder: " + e.getMessage()));
            }
        });
    }

    // ------------------------------------------------------------------ clipboard

    /** Copies the image to the system clipboard on the IO worker, then says how it went. */
    public static void copy(File file) {
        Util.getIoWorkerExecutor().execute(() -> {
            String error = copyImage(file);
            EvadeClient.mc().execute(() -> {
                if (error == null) Notifications.push("Screenshot", "Copied to the clipboard", true);
                else CommandManager.print("§7Couldn't copy the screenshot: " + error);
            });
        });
    }

    /** Puts the PNG on the system clipboard as an image. Null on success, else what went wrong. Blocking. */
    static String copyImage(File file) {
        if (!file.isFile()) return file.getName() + " no longer exists";
        Util.OperatingSystem os = Util.getOperatingSystem();
        // The game runs with AWT headless, so this is normally skipped for the system tools below.
        if (!GraphicsEnvironment.isHeadless() && os != Util.OperatingSystem.OSX) {
            try {
                BufferedImage img = ImageIO.read(file);
                if (img != null) {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageSelection(img), null);
                    return null;
                }
            } catch (Throwable ignored) {
                // fall back to the system tools
            }
        }
        String path = file.getAbsolutePath();
        return switch (os) {
            case WINDOWS -> exec(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-Command",
                    "$ErrorActionPreference='Stop';Add-Type -AssemblyName System.Windows.Forms;Add-Type -AssemblyName System.Drawing;"
                            + "$i=[System.Drawing.Image]::FromFile($env:EVADE_SCREENSHOT);"
                            + "try{[System.Windows.Forms.Clipboard]::SetImage($i)}finally{$i.Dispose()}"),
                    Map.of("EVADE_SCREENSHOT", path), null, "PowerShell");
            // The path goes in as an argument (argv), never into the script text.
            case OSX -> exec(List.of("osascript", "-e", "on run argv",
                    "-e", "set the clipboard to (read (POSIX file (item 1 of argv)) as «class PNGf»)",
                    "-e", "end run", path), Map.of(), null, "osascript");
            default -> {
                String wayland = System.getenv("WAYLAND_DISPLAY");
                List<String> errors = new ArrayList<>();
                if (wayland != null && !wayland.isBlank()) {
                    String e = exec(List.of("wl-copy", "--type", "image/png"), Map.of(), file, "wl-copy");
                    if (e == null) yield null;
                    errors.add(e);
                }
                String e = exec(List.of("xclip", "-selection", "clipboard", "-t", "image/png", "-i", path), Map.of(), null, "xclip");
                if (e == null) yield null;
                errors.add(e);
                yield String.join("; ", errors) + " (install wl-clipboard or xclip)";
            }
        };
    }

    /** Runs a clipboard tool and waits for it. Null when it exited cleanly, else a short reason. */
    private static String exec(List<String> command, Map<String, String> env, File stdin, String label) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        if (stdin != null) pb.redirectInput(stdin);
        try {
            Process p = pb.start();
            if (stdin == null) p.getOutputStream().close();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return label + " timed out";
            }
            return p.exitValue() == 0 ? null : label + " failed (exit " + p.exitValue() + ")";
        } catch (IOException e) {
            return label + " isn't available";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return label + " was interrupted";
        }
    }

    /** An image on the AWT clipboard. */
    private record ImageSelection(Image image) implements Transferable {
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
        @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.imageFlavor.equals(flavor); }
        @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return image;
        }
    }
}
