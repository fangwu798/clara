package com.clara.workspace.runtime;

import com.clara.kernel.*;
import com.clara.workspace.api.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 在工作区策略约束内扫描、读取和查找文件，并阻止路径逃逸及不受控的符号链接访问。
 *
 * <p>常规用法：使用部署级 {@link WorkspacePolicy} 构造实例，通过 {@link #scan} 获取受限快照，
 * 再按相对路径调用 {@link #read} 或使用 {@link #findFiles} 筛选文件。
 *
 * <p>注意：工作区根目录必须真实存在，传入路径必须是相对业务路径。扫描结果可能因文件数、单文件大小或
 * 总大小限制而截断；是否跟随符号链接由策略决定，即使开启也不会允许目标逃出工作区根目录。
 */
public final class FileSystemWorkspaceReader implements WorkspaceReader {
    private final WorkspacePolicy defaultPolicy;

    public FileSystemWorkspaceReader(WorkspacePolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        defaultPolicy = policy;
    }

    public WorkspaceSnapshot scan(WorkspaceRef workspace, WorkspacePolicy policy) {
        if (workspace == null || policy == null) {
            throw new IllegalArgumentException();
        }
        final Path root = realRoot(workspace);
        final List<FileDescriptor> files = new ArrayList<FileDescriptor>();
        final long[] total = {0L};
        final boolean[] truncated = {false};
        EnumSet<FileVisitOption> options = policy.isFollowLinks() ? EnumSet.of(FileVisitOption.FOLLOW_LINKS) : EnumSet.noneOf(FileVisitOption.class);
        try {
            Files.walkFileTree(root, options, Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(root) && (ignored(dir.getFileName().toString(), policy) || !inside(root, dir, policy.isFollowLinks())))
                        return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file) && !policy.isFollowLinks()) return FileVisitResult.CONTINUE;
                    if (!inside(root, file, policy.isFollowLinks()) || ignored(file.getFileName().toString(), policy) || !allowed(file, policy))
                        return FileVisitResult.CONTINUE;
                    if (files.size() >= policy.getMaxFiles() || attrs.size() > policy.getMaxFileBytes() || total[0] + attrs.size() > policy.getMaxTotalBytes()) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    files.add(descriptor(root, file, attrs));
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw WorkspaceErrors.error(ErrorCodes.WORKSPACE_ACCESS_DENIED, ErrorCategory.AUTHORIZATION);
        }
        Collections.sort(files, new Comparator<FileDescriptor>() {
            public int compare(FileDescriptor a, FileDescriptor b) {
                return a.getRelativePath().compareTo(b.getRelativePath());
            }
        });
        return new WorkspaceSnapshot(files, fingerprint(files), truncated[0], total[0]);
    }

    public FileDocument read(WorkspaceRef workspace, String relativePath) {
        Path root = realRoot(workspace);
        Path file = resolve(root, relativePath, defaultPolicy);
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) denied();
            if (attrs.size() > defaultPolicy.getMaxFileBytes()) {
                throw WorkspaceErrors.error(ErrorCodes.WORKSPACE_RESOURCE_LIMIT, ErrorCategory.RESOURCE_LIMIT);
            }
            if (!allowed(file, defaultPolicy) || !pathAllowed(root, file, defaultPolicy)) denied();
            byte[] bytes = Files.readAllBytes(file);
            return new FileDocument(descriptor(root, file, attrs, bytes), bytes);
        } catch (NoSuchFileException e) {
            throw WorkspaceErrors.error(ErrorCodes.WORKSPACE_FILE_NOT_FOUND, ErrorCategory.VALIDATION);
        } catch (IOException e) {
            throw WorkspaceErrors.error(ErrorCodes.WORKSPACE_ACCESS_DENIED, ErrorCategory.AUTHORIZATION);
        }
    }

    public List<FileDescriptor> findFiles(WorkspaceRef workspace, FileQuery query) {
        if (query == null) {
            throw new IllegalArgumentException();
        }
        List<FileDescriptor> result = new ArrayList<FileDescriptor>();
        String needle = query.getNameContains() == null ? null : query.getNameContains().toLowerCase(Locale.ROOT);
        for (FileDescriptor file : scan(workspace, defaultPolicy).getFiles()) {
            if (needle == null || file.getRelativePath().toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(file);
                if (result.size() == query.getMaximum()) break;
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static Path realRoot(WorkspaceRef workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException();
        }
        try {
            return workspace.getRoot().toRealPath();
        } catch (IOException e) {
            throw WorkspaceErrors.error(ErrorCodes.WORKSPACE_ACCESS_DENIED, ErrorCategory.AUTHORIZATION);
        }
    }

    private static Path resolve(Path root, String relative, WorkspacePolicy policy) {
        if (relative == null || relative.trim().isEmpty()) {
            throw new IllegalArgumentException();
        }
        Path candidate = Paths.get(relative);
        if (candidate.isAbsolute()) denied();
        candidate = root.resolve(candidate).normalize();
        if (!candidate.startsWith(root) || !pathAllowed(root, candidate, policy)) denied();
        try {
            if (Files.isSymbolicLink(candidate) && !policy.isFollowLinks()) denied();
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) denied();
            return real;
        } catch (NoSuchFileException e) {
            throw WorkspaceErrors.error(ErrorCodes.WORKSPACE_FILE_NOT_FOUND, ErrorCategory.VALIDATION);
        } catch (IOException e) {
            denied();
            return candidate;
        }
    }

    private static boolean inside(Path root, Path value, boolean follow) throws IOException {
        return !follow || value.toRealPath().startsWith(root);
    }

    private static boolean ignored(String name, WorkspacePolicy p) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith(".") || "target".equals(n) || "node_modules".equals(n)) return true;
        for (String v : p.getIgnoredNames()) if (n.equals(v.toLowerCase(Locale.ROOT))) return true;
        for (String v : p.getSensitiveNames()) if (n.equals(v.toLowerCase(Locale.ROOT))) return true;
        return n.equals(".env") || n.endsWith(".key") || n.endsWith(".pem") || n.endsWith(".p12");
    }

    private static boolean pathAllowed(Path root, Path file, WorkspacePolicy policy) {
        Path relative = root.relativize(file);
        for (Path part : relative) if (ignored(part.toString(), policy)) return false;
        return true;
    }

    private static boolean allowed(Path file, WorkspacePolicy p) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        for (String value : p.getAllowedExtensions()) if (ext.equals(value.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static FileDescriptor descriptor(Path root, Path file, BasicFileAttributes attrs) throws IOException {
        return descriptor(root, file, attrs, Files.readAllBytes(file));
    }

    private static FileDescriptor descriptor(Path root, Path file, BasicFileAttributes attrs, byte[] bytes) {
        return new FileDescriptor(root.relativize(file).toString().replace('\\', '/'), mime(file), attrs.size(), attrs.lastModifiedTime().toMillis(), sha(bytes));
    }

    private static String mime(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".doc")) return "application/msword";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".xls")) return "application/vnd.ms-excel";
        return "text/plain";
    }

    private static String fingerprint(List<FileDescriptor> files) {
        StringBuilder b = new StringBuilder();
        for (FileDescriptor f : files) b.append(f.getRelativePath()).append('\n').append(f.getSha256()).append('\n');
        return sha(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder b = new StringBuilder(64);
            for (byte v : digest) b.append(String.format("%02x", v & 255));
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void denied() {
        throw WorkspaceErrors.error(ErrorCodes.WORKSPACE_ACCESS_DENIED, ErrorCategory.AUTHORIZATION);
    }
}
