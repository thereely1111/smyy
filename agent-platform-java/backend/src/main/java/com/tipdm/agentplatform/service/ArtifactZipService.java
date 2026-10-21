package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.web.SessionArtifactController;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.ArtifactNode;
import com.tipdm.agentplatform.web.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 将会话产物树安全打包为 ZIP。 */
@Service
public class ArtifactZipService {

    private static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000;

    public void preflight(List<ArtifactNode> roots, String displayBase, Path sessionRoot,
                          Set<String> subSessions) {
        validate(roots, sessionRoot, subSessions, new HashSet<>(), new Counter());
    }

    public void pack(List<ArtifactNode> roots, String displayBase, Path sessionRoot,
                     Set<String> subSessions, OutputStream output) throws IOException {
        Counter counter = new Counter();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            packNodes(roots, displayBase == null ? "" : displayBase, sessionRoot, subSessions,
                    zip, counter, new HashSet<>());
        }
    }

    private void validate(List<ArtifactNode> nodes, Path sessionRoot, Set<String> subSessions,
                          Set<String> names, Counter counter) {
        if (nodes == null) {
            return;
        }
        for (ArtifactNode node : nodes) {
            String entryName = safeEntryName(node.name());
            if (!names.add(entryName)) {
                throw tooLarge("产物中存在重复 ZIP 条目: " + entryName);
            }
            counter.entries++;
            if (counter.entries > MAX_ENTRIES) {
                throw tooLarge("产物文件数量超过 ZIP 上限");
            }
            if ("file".equals(node.type())) {
                long size = node.size() == null ? 0 : node.size();
                counter.bytes += size;
                if (counter.bytes > MAX_TOTAL_BYTES) {
                    throw tooLarge("产物总大小超过 ZIP 上限");
                }
            }
            validate(node.children(), sessionRoot, subSessions, new HashSet<>(), counter);
        }
    }

    private void packNodes(List<ArtifactNode> nodes, String prefix, Path sessionRoot,
                           Set<String> subSessions, ZipOutputStream zip, Counter counter,
                           Set<String> names) throws IOException {
        if (nodes == null) {
            return;
        }
        for (ArtifactNode node : nodes) {
            String name = safeEntryName(node.name());
            String entryName = prefix.isBlank() ? name : prefix + "/" + name;
            if (!names.add(entryName)) {
                continue;
            }
            if ("dir".equals(node.type())) {
                zip.putNextEntry(new ZipEntry(entryName + "/"));
                zip.closeEntry();
                packNodes(node.children(), entryName, sessionRoot, subSessions, zip, counter, names);
                continue;
            }
            Path file = SessionArtifactController.resolveArtifactFile(
                    "", "", sessionRoot, subSessions, node.path());
            if (file == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "产物文件不存在: " + node.path());
            }
            zip.putNextEntry(new ZipEntry(entryName));
            Files.copy(file, zip);
            zip.closeEntry();
        }
    }

    private static String safeEntryName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "非法 ZIP 条目名");
        }
        return name;
    }

    private static ApiException tooLarge(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "ARTIFACT_ZIP_LIMIT", message);
    }

    private static final class Counter {
        private int entries;
        private long bytes;
    }
}
