package cn.xm1221.ygomc.common.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把 ygopro-scripts 的 zip 解进脚本根。zip 顶层目录会剥掉，{@code constant.lua} 必须落在根上。
 * 先写临时目录再换名，避免玩家看到半份脚本。见计划第 3.3 节。
 */
public final class ScriptInstaller {

    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;

    private ScriptInstaller() {
    }

    /** 用已经写好的目录换掉目标。目标原来的内容先改名，失败再挪回去。 */
    public static void replaceDirectory(Path incoming, Path destination) throws IOException {
        swap(incoming, destination);
    }

    public static int install(Path zip, Path destination) throws IOException {
        Path parent = destination.getParent() == null ? Path.of(".") : destination.getParent();
        Files.createDirectories(parent);
        Path incoming = parent.resolve(destination.getFileName().toString() + ".incoming");
        deleteTree(incoming);
        Files.createDirectories(incoming);
        try {
            int files = extract(zip, incoming);
            if (!Files.isRegularFile(incoming.resolve("constant.lua"))) {
                throw new IOException("解压后没有 constant.lua");
            }
            swap(incoming, destination);
            return files;
        } catch (IOException e) {
            deleteTree(incoming);
            throw e;
        }
    }

    private static int extract(Path zip, Path dest) throws IOException {
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                names.add(entry.getName().replace('\\', '/'));
                if (names.size() > MAX_ENTRIES) {
                    throw new IOException("脚本 zip 条目过多");
                }
            }
            String strip = commonTop(names);
            int files = 0;
            long bytes = 0;
            entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("__MACOSX/") || name.contains("/__MACOSX/")) {
                    continue;
                }
                String rel = name;
                if (strip != null && rel.startsWith(strip)) {
                    rel = rel.substring(strip.length());
                }
                if (rel.isBlank()) {
                    continue;
                }
                Path target = dest.resolve(rel).normalize();
                if (!target.startsWith(dest)) {
                    throw new IOException("脚本 zip 路径越界：" + name);
                }
                if (entry.isDirectory() || rel.endsWith("/")) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = archive.getInputStream(entry)) {
                    byte[] buf = new byte[8192];
                    try (var out = Files.newOutputStream(target)) {
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            bytes += n;
                            if (bytes > MAX_BYTES) {
                                throw new IOException("脚本 zip 解压后超过 " + MAX_BYTES + " 字节");
                            }
                            out.write(buf, 0, n);
                        }
                    }
                }
                files++;
            }
            return files;
        }
    }

    static String commonTop(List<String> names) {
        String top = null;
        boolean nested = false;
        for (String name : names) {
            if (name.isBlank()) {
                continue;
            }
            int slash = name.indexOf('/');
            if (slash < 0) {
                return null;
            }
            nested = true;
            String first = name.substring(0, slash + 1);
            if (top == null) {
                top = first;
            } else if (!top.equals(first)) {
                return null;
            }
        }
        return nested ? top : null;
    }

    private static void swap(Path incoming, Path destination) throws IOException {
        Path backup = destination.resolveSibling(destination.getFileName().toString() + ".bak");
        deleteTree(backup);
        if (Files.exists(destination)) {
            move(destination, backup);
        }
        try {
            move(incoming, destination);
            deleteTree(backup);
        } catch (IOException e) {
            if (!Files.exists(destination) && Files.exists(backup)) {
                move(backup, destination);
            }
            throw e;
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
