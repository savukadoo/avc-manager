package local.cc3.manager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.UUID;

/** Shared commit boundary for imported/uploaded local media. Existing names are preserved. */
public final class MediaFileStore {
    private MediaFileStore() { }
    public static String safeName(String input) throws IOException {
        if (input == null) throw new IOException("Choose a file name");
        String name = Normalizer.normalize(input.trim(), Normalizer.Form.NFC);
        if (name.isEmpty() || name.startsWith(".") || name.endsWith(".")
                || name.getBytes(StandardCharsets.UTF_8).length > 180)
            throw new IOException("File name is empty, hidden or too long");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127 || "/\\:<>|?*".indexOf(c) >= 0)
                throw new IOException("File name contains a path or unsupported character");
            if (Character.isHighSurrogate(c)) {
                if (++i >= name.length() || !Character.isLowSurrogate(name.charAt(i))) throw new IOException("Invalid file name");
            } else if (Character.isLowSurrogate(c)) throw new IOException("Invalid file name");
        }
        return name;
    }
    public static synchronized File incoming(File root, String prefix) throws IOException {
        if (!prefix.matches("[a-z]{1,12}")) throw new IOException("Invalid staging prefix");
        File directory = new File(root.getCanonicalFile(), ".incoming");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("CC3 media storage is unavailable");
        if (!directory.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) throw new IOException("Invalid media staging path");
        File file = new File(directory, prefix + "-" + UUID.randomUUID() + ".part");
        if (!file.createNewFile()) throw new IOException("Cannot prepare incoming file");
        return file;
    }
    public static synchronized File commit(File root, File temporary, String originalName) throws IOException {
        String name = safeName(originalName);
        File directory = root.getCanonicalFile();
        File staging = new File(directory, ".incoming").getCanonicalFile();
        if (!temporary.getCanonicalFile().getParentFile().equals(staging)
                || Files.isSymbolicLink(temporary.toPath()) || !temporary.isFile() || temporary.length() == 0)
            throw new IOException("Incoming media is empty or outside the staging folder");
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, extension = dot > 0 ? name.substring(dot) : "";
        for (int copy = 1; copy <= 10000; copy++) {
            File destination = new File(directory, copy == 1 ? name : base + " (" + copy + ")" + extension);
            if (!destination.getCanonicalFile().getParentFile().equals(directory)) throw new IOException("Invalid media destination");
            if (destination.exists() || Files.isSymbolicLink(destination.toPath())) continue;
            if (!temporary.renameTo(destination)) throw new IOException("Cannot finish saving media on the CC3");
            return destination;
        }
        throw new IOException("Too many files have this name");
    }
    public static void cleanOldUploads(File root, long now) {
        File[] files = new File(root, ".incoming").listFiles();
        if (files == null) return;
        for (File file : files) if (file.isFile() && !Files.isSymbolicLink(file.toPath())
                && file.getName().matches("upload-[a-f0-9-]+\\.part") && now - file.lastModified() > 86400000L) file.delete();
    }
}
